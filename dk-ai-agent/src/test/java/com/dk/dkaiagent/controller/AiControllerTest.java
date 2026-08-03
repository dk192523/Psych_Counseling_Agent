package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.cache.AnswerCache;
import com.dk.dkaiagent.agent.counseling.CounselingAgentExecutor;
import com.dk.dkaiagent.agent.counseling.CounselingStreamEvent;
import com.dk.dkaiagent.history.ConversationDetail;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationMessage;
import com.dk.dkaiagent.history.ConversationSummary;
import com.dk.dkaiagent.history.MemoryStats;
import com.dk.dkaiagent.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiControllerTest {

    private static final long OWNER_ID = 42L;

    private AiController controller;
    private CounselingApp counselingApp;
    private ConversationHistoryService conversationHistoryService;
    private CounselingAgentExecutor counselingAgentExecutor;
    private AnswerCache answerCache;
    private MockedStatic<CurrentUser> currentUser;

    @BeforeEach
    void setUp() {
        controller = new AiController();
        counselingApp = mock(CounselingApp.class);
        conversationHistoryService = mock(ConversationHistoryService.class);
        counselingAgentExecutor = mock(CounselingAgentExecutor.class);
        ReflectionTestUtils.setField(controller, "counselingApp", counselingApp);
        ReflectionTestUtils.setField(controller, "conversationHistoryService", conversationHistoryService);
        ReflectionTestUtils.setField(controller, "counselingAgentExecutor", counselingAgentExecutor);
        answerCache = new AnswerCache(true, 600, 1000);
        ReflectionTestUtils.setField(controller, "answerCache", answerCache);
        // 认证主体由 B2 的 CurrentUser 从安全上下文读取；单测中以静态桩固定主体 id。
        currentUser = mockStatic(CurrentUser.class);
        currentUser.when(CurrentUser::requireUserId).thenReturn(OWNER_ID);
    }

    @AfterEach
    void releaseCurrentUserMock() {
        currentUser.close();
    }

    private static ConversationDetail detail(String chatId) {
        Instant now = Instant.parse("2026-07-22T08:00:00Z");
        return new ConversationDetail(
                chatId,
                "title",
                now,
                now,
                List.of(new ConversationMessage(1L, "user", "message", now)),
                new MemoryStats(1, 1000, 0, 0, "", null)
        );
    }

    @Test
    void syncEndpointUsesRagConversation() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRag(OWNER_ID, "message", "chat-id")).thenReturn("answer");

        String result = controller.doChatWithCounselingSync("message", "chat-id");

        assertSame("answer", result);
        verify(counselingApp).doChatWithRag(OWNER_ID, "message", "chat-id");
    }

    @Test
    void sseEndpointPreservesWhitespaceInStructuredEvents() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        Flux<String> expected = Flux.just("## ", "标题", "\n\n", "- ", "项目", "[DONE]");
        when(counselingApp.doChatWithRagByStream(OWNER_ID, "message", "chat-id")).thenReturn(expected);

        List<ServerSentEvent<AiController.ChatStreamEvent>> result = controller
                .doChatWithCounselingSSE("message", "chat-id")
                .collectList()
                .block();

        assertEquals(6, result.size());
        assertEquals(new AiController.ChatStreamEvent("delta", "## "), result.get(0).data());
        assertEquals(new AiController.ChatStreamEvent("delta", "\n\n"), result.get(2).data());
        assertEquals(new AiController.ChatStreamEvent("delta", "- "), result.get(3).data());
        assertEquals(new AiController.ChatStreamEvent("done", ""), result.get(5).data());
        verify(counselingApp).doChatWithRagByStream(OWNER_ID, "message", "chat-id");
    }

    @Test
    void deepSseEndpointUsesAgentAndPreservesProgressEvents() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingAgentExecutor.stream("message", "chat-id", OWNER_ID)).thenReturn(Flux.just(
                CounselingStreamEvent.status("planning", "正在规划"),
                CounselingStreamEvent.delta("回答", "deep", false),
                CounselingStreamEvent.done("deep", false)
        ));

        List<ServerSentEvent<AiController.ChatStreamEvent>> result = controller
                .doChatWithCounselingSSE("message", "chat-id", true)
                .collectList()
                .block();

        assertEquals(3, result.size());
        assertEquals("status", result.get(0).data().type());
        assertEquals("planning", result.get(0).data().phase());
        assertEquals("deep", result.get(1).data().effectiveMode());
        assertEquals("done", result.get(2).data().type());
        verify(counselingAgentExecutor).stream("message", "chat-id", OWNER_ID);
        verify(counselingApp, never()).doChatWithRagByStream(anyLong(), anyString(), anyString());
    }

    @Test
    void postSseRequestUsesBodyContract() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRagByStream(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("answer", "[DONE]"));

        List<ServerSentEvent<AiController.ChatStreamEvent>> result = controller
                .doChatWithCounselingSSE(new AiController.ChatRequest("message", "chat-id", false))
                .collectList()
                .block();

        assertEquals(2, result.size());
        assertEquals("answer", result.get(0).data().content());
        assertEquals("done", result.get(1).data().type());
    }

    @Test
    void conversationEndpointsDelegateToHistoryService() {
        Instant now = Instant.parse("2026-07-22T08:00:00Z");
        ConversationSummary summary = new ConversationSummary("chat-id", "title", now, now, 1, "message", 1000);
        MemoryStats memory = new MemoryStats(1, 1000, 0, 0, "", null);
        ConversationDetail detail = new ConversationDetail(
                "chat-id",
                "title",
                now,
                now,
                List.of(new ConversationMessage(1L, "user", "message", now)),
                memory
        );
        when(conversationHistoryService.createConversation(OWNER_ID)).thenReturn(summary);
        when(conversationHistoryService.listConversations(OWNER_ID)).thenReturn(List.of(summary));
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID)).thenReturn(Optional.of(detail));
        when(conversationHistoryService.delete("chat-id", OWNER_ID)).thenReturn(true);

        assertSame(summary, controller.createConversation());
        assertEquals(List.of(summary), controller.listConversations());
        assertSame(detail, controller.getConversation("chat-id"));
        controller.deleteConversation("chat-id");

        verify(conversationHistoryService).createConversation(OWNER_ID);
        verify(conversationHistoryService).listConversations(OWNER_ID);
        verify(conversationHistoryService).getConversation("chat-id", OWNER_ID);
        verify(conversationHistoryService).delete("chat-id", OWNER_ID);
        verify(counselingApp).clearConversationMemory("chat-id");
    }

    @Test
    void missingConversationReturnsNotFound() {
        when(conversationHistoryService.getConversation("missing", OWNER_ID)).thenReturn(Optional.empty());
        when(conversationHistoryService.delete("missing", OWNER_ID)).thenReturn(false);

        ResponseStatusException getError = assertThrows(
                ResponseStatusException.class,
                () -> controller.getConversation("missing")
        );
        ResponseStatusException deleteError = assertThrows(
                ResponseStatusException.class,
                () -> controller.deleteConversation("missing")
        );

        assertEquals(HttpStatus.NOT_FOUND, getError.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, deleteError.getStatusCode());
        verify(counselingApp, never()).clearConversationMemory("missing");
    }

    @Test
    void chatStreamReturnsNotFoundForForeignConversationBeforeAnyDownstream() {
        // 跨用户会话与"不存在"同形：开流前的 getConversation owner 过滤返回空 → 404，
        // 且绝不进入聊天下游。
        when(conversationHistoryService.getConversation("other-chat", OWNER_ID)).thenReturn(Optional.empty());

        ResponseStatusException syncError = assertThrows(
                ResponseStatusException.class,
                () -> controller.doChatWithCounselingSync("message", "other-chat")
        );
        ResponseStatusException sseError = assertThrows(
                ResponseStatusException.class,
                () -> controller.doChatWithCounselingSSE("message", "other-chat", true)
        );

        assertEquals(HttpStatus.NOT_FOUND, syncError.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, sseError.getStatusCode());
        verify(counselingApp, never()).doChatWithRag(anyLong(), anyString(), anyString());
        verify(counselingApp, never()).doChatWithRagByStream(anyLong(), anyString(), anyString());
        verify(counselingAgentExecutor, never()).stream(anyString(), anyString(), anyLong());
    }

    @Test
    void repeatedFastRequestWithinTtlServesCachedStreamWithoutSecondGeneration() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRagByStream(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("answer", "[DONE]"));

        controller.doChatWithCounselingSSE("message", "chat-id").collectList().block();
        List<ServerSentEvent<AiController.ChatStreamEvent>> second = controller
                .doChatWithCounselingSSE("message", "chat-id").collectList().block();

        assertEquals(2, second.size());
        assertEquals("answer", second.get(0).data().content());
        assertEquals("done", second.get(1).data().type());
        // 第二次命中缓存：生成管线只执行一次。
        verify(counselingApp, times(1)).doChatWithRagByStream(OWNER_ID, "message", "chat-id");
    }

    @Test
    void repeatedDeepRequestWithinTtlServesCachedStreamWithoutSecondAgentRun() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingAgentExecutor.stream("message", "chat-id", OWNER_ID)).thenReturn(Flux.just(
                CounselingStreamEvent.status("planning", "正在规划"),
                CounselingStreamEvent.delta("回答", "deep", false),
                CounselingStreamEvent.done("deep", false)
        ));

        controller.doChatWithCounselingSSE("message", "chat-id", true).collectList().block();
        List<ServerSentEvent<AiController.ChatStreamEvent>> second = controller
                .doChatWithCounselingSSE("message", "chat-id", true).collectList().block();

        assertEquals(3, second.size());
        assertEquals("status", second.get(0).data().type());
        assertEquals("done", second.get(2).data().type());
        // 深度模式同样命中缓存：agent 只跑一次。
        verify(counselingAgentExecutor, times(1)).stream("message", "chat-id", OWNER_ID);
    }

    @Test
    void changedHistoryFingerprintMissesCacheAndRegenerates() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRagByStream(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("answer", "[DONE]"));
        // 两次请求之间插入了新轮次：指纹（消息数）变化 → 不应命中缓存。
        when(conversationHistoryService.countMessages("chat-id")).thenReturn(1, 2);

        controller.doChatWithCounselingSSE("message", "chat-id").collectList().block();
        controller.doChatWithCounselingSSE("message", "chat-id").collectList().block();

        verify(counselingApp, times(2)).doChatWithRagByStream(OWNER_ID, "message", "chat-id");
    }
}
