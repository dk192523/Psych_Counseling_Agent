package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.agent.counseling.CounselingAgentExecutor;
import com.dk.dkaiagent.agent.counseling.CounselingStreamEvent;
import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.cache.AnswerCache;
import com.dk.dkaiagent.history.ConversationDetail;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationSummary;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
    private AnswerCache answerCache;

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
     * 同步调用 AI 心理咨询师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/counseling/chat/sync")
    public String doChatWithCounselingSync(String message, String chatId) {
        long ownerId = requireConversationOwner(chatId);
        return counselingApp.doChatWithRag(ownerId, message, chatId);
    }

    /** 主页面使用 POST 传输咨询内容，避免敏感文本出现在 URL 与代理访问日志中。 */
    @PostMapping(value = "/counseling/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> doChatWithCounselingSSE(@RequestBody ChatRequest request) {
        return streamCounselingChat(request.message(), request.chatId(), request.deepThinking());
    }

    /**
     * 兼容旧客户端的 GET 流式入口。新客户端应调用同路径的 POST 接口。
     */
    @GetMapping(value = "/counseling/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> doChatWithCounselingSSE(
            String message,
            String chatId,
            @RequestParam(defaultValue = "false") boolean deepThinking) {
        return streamCounselingChat(message, chatId, deepThinking);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamCounselingChat(
            String message,
            String chatId,
            boolean deepThinking) {
        // 开流前完成所有权校验：跨用户与不存在一律 404，绝不带着他人主体进入下游。
        long ownerId = requireConversationOwner(chatId);

        if (!answerCache.enabled()) {
            return buildChatEvents(ownerId, message, chatId, deepThinking)
                    .map(this::toServerSentEvent);
        }

        // 历史指纹取当前消息数：中间插入新轮次即变化，保证只缓存"完全相同的重复请求"。
        long fingerprint = conversationHistoryService.countMessages(chatId);
        String cacheKey = answerCache.key(chatId, deepThinking, message, fingerprint);
        Optional<List<AnswerCache.CachedEvent>> hit = answerCache.get(cacheKey);
        if (hit.isPresent()) {
            // 命中：重复请求视为同一轮，直接回放且不重复落库。
            return Flux.fromIterable(hit.get())
                    .map(AiController::toStreamEvent)
                    .map(this::toServerSentEvent);
        }

        List<AnswerCache.CachedEvent> buffer = Collections.synchronizedList(new ArrayList<>());
        return buildChatEvents(ownerId, message, chatId, deepThinking)
                .doOnNext(event -> buffer.add(toCachedEvent(event)))
                .doOnComplete(() -> answerCache.put(cacheKey, buffer))
                .map(this::toServerSentEvent);
    }

    /** 快速/深度两条分支汇合点；缓存包在其外，两种模式都覆盖。 */
    private Flux<ChatStreamEvent> buildChatEvents(
            long ownerId, String message, String chatId, boolean deepThinking) {
        return deepThinking
                ? counselingAgentExecutor.stream(message, chatId, ownerId).map(ChatStreamEvent::from)
                : counselingApp.doChatWithRagByStream(ownerId, message, chatId)
                .map(chunk -> "[DONE]".equals(chunk)
                        ? new ChatStreamEvent("done", "")
                        : new ChatStreamEvent("delta", chunk));
    }

    private ServerSentEvent<ChatStreamEvent> toServerSentEvent(ChatStreamEvent event) {
        return ServerSentEvent.<ChatStreamEvent>builder().data(event).build();
    }

    private static AnswerCache.CachedEvent toCachedEvent(ChatStreamEvent event) {
        return new AnswerCache.CachedEvent(
                event.type(), event.content(), event.phase(), event.effectiveMode(), event.fallback());
    }

    private static ChatStreamEvent toStreamEvent(AnswerCache.CachedEvent cached) {
        return new ChatStreamEvent(
                cached.type(), cached.content(), cached.phase(), cached.effectiveMode(), cached.fallback());
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

    public record ChatRequest(String message, String chatId, boolean deepThinking) {
    }

    /**
     * SSE 流式调用 AI 心理咨询师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/counseling/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithCounselingServerSentEvent(String message, String chatId) {
        long ownerId = requireConversationOwner(chatId);
        return counselingApp.doChatWithRagByStream(ownerId, message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式调用 AI 心理咨询师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/counseling/chat/sse_emitter")
    public SseEmitter doChatWithCounselingServerSseEmitter(String message, String chatId) {
        long ownerId = requireConversationOwner(chatId);
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 获取 Flux 响应式数据流并且直接通过订阅推送给 SseEmitter
        counselingApp.doChatWithRagByStream(ownerId, message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        // 返回
        return sseEmitter;
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
     * 会话历史服务层的归属守卫与并发删除复核统一抛 IllegalStateException，语义都是
     * "该会话对当前调用方不可用"（跨用户 / 校验后被并发删除）：转 404 与本控制器其余端点
     * 的"跨用户与不存在同形"语义对齐，避免守卫命中被误读为 500 故障。
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleConversationGuard(IllegalStateException exception) {
        // 无响应体：与 deleteConversation 的裸 404 同形；异常信息保留在服务端日志。
    }

}
