package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.agent.counseling.CounselingAgentExecutor;
import com.dk.dkaiagent.agent.counseling.CounselingStreamEvent;
import com.dk.dkaiagent.agent.counseling.CounselingTurnPipeline;
import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.history.ConversationDetail;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationSummary;
import com.dk.dkaiagent.history.ConversationUnavailableException;
import com.dk.dkaiagent.security.CurrentUser;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private CounselingApp counselingApp;

    @Resource
    private ConversationHistoryService conversationHistoryService;

    @Resource
    private CounselingAgentExecutor counselingAgentExecutor;

    @Resource
    private CounselingTurnPipeline counselingTurnPipeline;

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummary createConversation() {
        long ownerId = CurrentUser.requireUserId();
        return conversationHistoryService.createConversation(ownerId);
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        long ownerId = CurrentUser.requireUserId();
        return conversationHistoryService.listConversations(ownerId);
    }

    @GetMapping("/conversations/{id}")
    public ConversationDetail getConversation(@PathVariable String id) {
        long ownerId = CurrentUser.requireUserId();
        // 跨用户访问与"不存在"同形：服务层按 owner 过滤返回空 Optional，这里统一 404，不以 403 泄露存在性。
        return conversationHistoryService.getConversation(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@PathVariable String id) {
        long ownerId = CurrentUser.requireUserId();
        if (!conversationHistoryService.delete(id, ownerId)) {
            // 跨用户删除同样走"不存在"语义：delete 按 owner 过滤返回 false，统一 404。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        counselingApp.clearConversationMemory(id);
    }

    /**
     * 同步调用 AI 心理咨询师应用（POST：正文走请求体）。
     *
     * <p>原 GET 形式已移除。咨询正文一旦进入 URL query，就会被 nginx access log、浏览器历史、
     * 中间代理与 CDN 日志留档——这类文本恰恰是本系统里最敏感的数据，且这些日志的留存周期
     * 和访问权限都不在应用控制范围内。</p>
     */
    @PostMapping("/counseling/chat/sync")
    public String doChatWithCounselingSync(@RequestBody ChatRequest request) {
        return doChatWithCounselingSync(request.message(), request.chatId(), request.clientMsgId());
    }

    /** 无映射注解的 Java 入口：供进程内调用与单测，不对外暴露 HTTP 形式。 */
    public String doChatWithCounselingSync(String message, String chatId) {
        return doChatWithCounselingSync(message, chatId, null);
    }

    public String doChatWithCounselingSync(String message, String chatId, String clientMsgId) {
        long ownerId = requireConversationOwner(chatId);
        return counselingApp.doChatWithRag(ownerId, message, chatId, clientMsgId);
    }

    /** 主页面使用 POST 传输咨询内容，避免敏感文本出现在 URL 与代理访问日志中。 */
    @PostMapping(value = "/counseling/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> doChatWithCounselingSSE(@RequestBody ChatRequest request) {
        return doChatWithCounselingSSE(request.message(), request.chatId(), request.deepThinking(),
                request.clientMsgId());
    }

    /**
     * 无映射注解的 Java 入口：供进程内调用与单测。
     *
     * <p>同路径的 GET 兼容入口连同 {@code server_sent_event}、{@code sse_emitter} 两个旧端点
     * 一并移除：它们与本方法功能完全重合，唯一区别就是把咨询正文放进 URL。前端正式链路是
     * POST + fetch 流式读取（见 {@code api/index.js} 的 connectSSE），没有仍依赖 GET 的客户端。</p>
     */
    public Flux<ServerSentEvent<ChatStreamEvent>> doChatWithCounselingSSE(
            String message,
            String chatId,
            boolean deepThinking) {
        return doChatWithCounselingSSE(message, chatId, deepThinking, null);
    }

    /** {@code clientMsgId} 为前端为本轮生成的幂等键：SSE 中断重发时后端不会重复归档用户消息。 */
    public Flux<ServerSentEvent<ChatStreamEvent>> doChatWithCounselingSSE(
            String message,
            String chatId,
            boolean deepThinking,
            String clientMsgId) {
        // 开流前完成所有权校验：跨用户与不存在一律 404，绝不带着他人主体进入下游。
        long ownerId = requireConversationOwner(chatId);

        // 归档、快速/深度分流与事件映射统一收口在 pipeline，控制器只保留 HTTP 契约。
        return counselingTurnPipeline
                .run(new CounselingTurnPipeline.CounselingTurnRequest(
                        ownerId, chatId, message, clientMsgId, deepThinking))
                .map(ChatStreamEvent::from)
                .map(this::toServerSentEvent);
    }

    private ServerSentEvent<ChatStreamEvent> toServerSentEvent(ChatStreamEvent event) {
        return ServerSentEvent.<ChatStreamEvent>builder().data(event).build();
    }

    /** Backwards-compatible overload used by existing Java callers and tests. */
    public Flux<ServerSentEvent<ChatStreamEvent>> doChatWithCounselingSSE(String message, String chatId) {
        return doChatWithCounselingSSE(message, chatId, false);
    }

    public record ChatStreamEvent(
            String type,
            String content,
            String phase,
            String effectiveMode,
            boolean fallback) {

        public ChatStreamEvent(String type, String content) {
            this(type, content, null, "standard", false);
        }

        static ChatStreamEvent from(CounselingStreamEvent event) {
            return new ChatStreamEvent(
                    event.type(),
                    event.content(),
                    event.phase(),
                    event.effectiveMode(),
                    event.fallback()
            );
        }
    }

    /** {@code clientMsgId} 可空：旧客户端不携带时后端不做幂等去重，行为与历史版本一致。 */
    public record ChatRequest(String message, String chatId, boolean deepThinking, String clientMsgId) {

        public ChatRequest(String message, String chatId, boolean deepThinking) {
            this(message, chatId, deepThinking, null);
        }
    }

    /**
     * 聊天流入口统一校验：先取认证主体，再在订阅/下游之前完成会话所有权核验。
     * 跨用户与不存在共用 getConversation 的 owner 过滤结果，同形 404，不探测会话存在性。
     */
    private long requireConversationOwner(String chatId) {
        long ownerId = CurrentUser.requireUserId();
        conversationHistoryService.getConversation(chatId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversation not found"));
        return ownerId;
    }

    /**
     * 会话历史服务层的归属守卫与并发删除复核统一抛专用异常，语义都是
     * “该会话对当前调用方不可用”（跨用户 / 校验后被并发删除）：转 404 与本控制器其余端点
     * 的“跨用户与不存在同形”语义对齐。其他 IllegalStateException 保持 500，避免掩盖程序故障。
     */
    @ExceptionHandler(ConversationUnavailableException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleConversationGuard(ConversationUnavailableException exception) {
        // 无响应体：与 deleteConversation 的裸 404 同形；异常信息保留在服务端日志。
    }

}
