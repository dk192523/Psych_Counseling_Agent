package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.agent.counseling.CounselingAgentExecutor;
import com.dk.dkaiagent.agent.counseling.CounselingStreamEvent;
import com.dk.dkaiagent.agent.counseling.CounselingTurnPipeline;
import com.dk.dkaiagent.history.ConversationDetail;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationMessage;
import com.dk.dkaiagent.history.ConversationSummary;
import com.dk.dkaiagent.history.ConversationUnavailableException;
import com.dk.dkaiagent.history.MemoryStats;
import com.dk.dkaiagent.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerTest {

    private static final long OWNER_ID = 42L;

    private AiController controller;
    private CounselingApp counselingApp;
    private ConversationHistoryService conversationHistoryService;
    private CounselingAgentExecutor counselingAgentExecutor;
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
        // 控制器把归档与分流收口给 pipeline：这里装配真实 pipeline + mock 依赖，
        // 让用例同时覆盖"pipeline 正确接线"与"事件映射不变"。
        CounselingTurnPipeline pipeline = new CounselingTurnPipeline();
        ReflectionTestUtils.setField(pipeline, "counselingApp", counselingApp);
        ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", counselingAgentExecutor);
        ReflectionTestUtils.setField(controller, "counselingTurnPipeline", pipeline);
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
        when(counselingApp.doChatWithRag(OWNER_ID, "message", "chat-id", null)).thenReturn("answer");

        String result = controller.doChatWithCounselingSync("message", "chat-id");

        assertSame("answer", result);
        verify(counselingApp).doChatWithRag(OWNER_ID, "message", "chat-id", null);
    }

    @Test
    void sseEndpointPreservesWhitespaceInStructuredEvents() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        Flux<String> expected = Flux.just("## ", "标题", "\n\n", "- ", "项目", "[DONE]");
        when(counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id")).thenReturn(expected);

        List<ServerSentEvent<AiController.ChatStreamEvent>> result = controller
                .doChatWithCounselingSSE("message", "chat-id")
                .collectList()
                .block();

        assertEquals(6, result.size());
        assertEquals(new AiController.ChatStreamEvent("delta", "## "), result.get(0).data());
        assertEquals(new AiController.ChatStreamEvent("delta", "\n\n"), result.get(2).data());
        assertEquals(new AiController.ChatStreamEvent("delta", "- "), result.get(3).data());
        assertEquals(new AiController.ChatStreamEvent("done", ""), result.get(5).data());
        // pipeline 收口归档：快速链路也只允许这一处用户消息落库。
        verify(counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message", null);
        verify(counselingApp).doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id");
    }

    @Test
    void deepSseEndpointUsesAgentAndPreservesProgressEvents() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingAgentExecutor.prepareAndAnswer("message", "chat-id", OWNER_ID)).thenReturn(Flux.just(
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
        verify(counselingAgentExecutor).prepareAndAnswer("message", "chat-id", OWNER_ID);
        verify(counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message", null);
        verify(counselingApp, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
    }

    @Test
    void postSseRequestUsesBodyContract() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id"))
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
    void syncRequestUsesBodyContract() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRag(OWNER_ID, "message", "chat-id", null)).thenReturn("answer");

        String result = controller.doChatWithCounselingSync(
                new AiController.ChatRequest("message", "chat-id", false));

        assertSame("answer", result);
        verify(counselingApp).doChatWithRag(OWNER_ID, "message", "chat-id", null);
    }

    @Test
    void clientMsgIdThreadThroughBothModesForIdempotentArchive() {
        // U9 幂等：前端生成的 clientMsgId 必须经 pipeline 原样落到归档入口，
        // 由 ConversationHistoryService 的唯一索引在落库处去重。
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("answer", "[DONE]"));
        when(counselingAgentExecutor.prepareAndAnswer("message", "chat-id", OWNER_ID))
                .thenReturn(Flux.just(CounselingStreamEvent.done("deep", false)));

        controller.doChatWithCounselingSSE(
                new AiController.ChatRequest("message", "chat-id", false, "client-msg-1"))
                .collectList().block();
        controller.doChatWithCounselingSSE(
                new AiController.ChatRequest("message", "chat-id", true, "client-msg-2"))
                .collectList().block();

        verify(counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message", "client-msg-1");
        verify(counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message", "client-msg-2");
        verify(counselingApp).doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id");
        verify(counselingAgentExecutor).prepareAndAnswer("message", "chat-id", OWNER_ID);
    }

    @Test
    void counselingTextIsNeverExposedOnAnyHttpGetMapping() throws Exception {
        // C8 回归：控制器不得再有任何把咨询正文放进 URL query 的 GET 入口。
        // 断言在反射层做，因为端点一旦被"顺手加回来"，业务单测不会有任何反应。
        List<String> getMappedChatPaths = java.util.Arrays.stream(AiController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .filter(path -> path.contains("counseling/chat"))
                .toList();

        assertEquals(List.of(), getMappedChatPaths,
                "咨询正文必须走请求体：GET query 会被 nginx/浏览器历史/代理日志留档");
    }

    @Test
    void controllerOnlyMapsConversationUnavailableFailuresToNotFound() throws Exception {
        ExceptionHandler annotation = AiController.class
                .getMethod("handleConversationGuard", ConversationUnavailableException.class)
                .getAnnotation(ExceptionHandler.class);

        assertEquals(List.of(ConversationUnavailableException.class), List.of(annotation.value()));
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
        verify(counselingApp, never()).doChatWithRag(anyLong(), anyString(), anyString(), any());
        verify(counselingApp, never()).prepareConversationTurn(anyLong(), anyString(), anyString(), any());
        verify(counselingApp, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
        verify(counselingAgentExecutor, never()).prepareAndAnswer(anyString(), anyString(), anyLong());
    }

    @Test
    void repeatedMessagesRemainDistinctConversationTurns() {
        when(conversationHistoryService.getConversation("chat-id", OWNER_ID))
                .thenReturn(Optional.of(detail("chat-id")));
        when(counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("first", "[DONE]"), Flux.just("second", "[DONE]"));

        List<ServerSentEvent<AiController.ChatStreamEvent>> first = controller
                .doChatWithCounselingSSE("message", "chat-id").collectList().block();
        List<ServerSentEvent<AiController.ChatStreamEvent>> second = controller
                .doChatWithCounselingSSE("message", "chat-id").collectList().block();

        assertEquals("first", first.get(0).data().content());
        assertEquals("second", second.get(0).data().content());
        verify(counselingApp, org.mockito.Mockito.times(2))
                .doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id");
    }
}
