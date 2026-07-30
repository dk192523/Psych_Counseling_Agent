package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.integration.aiworker.AiWorkerClient;
import com.dk.dkaiagent.integration.aiworker.AiWorkerContracts;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import com.dk.dkaiagent.rag.TranscriptSearchService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiCounselingAgentExecutorTest {

    private static final long OWNER_ID = 42L;

    private static final ExecutorService AGENT_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("agent-test-", 0).factory());
    private static final Scheduler AGENT_SCHEDULER = Schedulers.fromExecutor(AGENT_EXECUTOR);

    @AfterAll
    static void releaseAgentRuntime() {
        AGENT_SCHEDULER.dispose();
        AGENT_EXECUTOR.close();
    }

    @Test
    void disabledAgentPreparesOnceThenUsesJavaFallback() {
        Fixture fixture = new Fixture();
        fixture.properties.setEnabled(false);
        when(fixture.counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("answer", "[DONE]"));

        List<CounselingStreamEvent> events = fixture.executor()
                .stream("message", "chat-id", OWNER_ID)
                .collectList()
                .block();

        assertEquals(List.of("fallback", "delta", "done"), eventTypes(events));
        assertTrue(events.get(0).fallback());
        assertEquals("standard", events.get(1).effectiveMode());
        verify(fixture.counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message");
        verify(fixture.counselingApp).doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id");
        verify(fixture.counselingApp, never()).doChatWithRagByStream(anyLong(), anyString(), anyString());
    }

    @Test
    void plannerFailureFallsBackBeforeAnyAnswerDelta() {
        Fixture fixture = new Fixture();
        when(fixture.historyService.getRecentMessages("chat-id", fixture.properties.getHistoryMessages()))
                .thenReturn(List.of());
        when(fixture.chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("planner unavailable"));
        when(fixture.counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("stable", "[DONE]"));

        List<CounselingStreamEvent> events = fixture.executor()
                .stream("message", "chat-id", OWNER_ID)
                .collectList()
                .block();

        assertEquals(List.of("status", "fallback", "delta", "done"), eventTypes(events));
        assertEquals("planning", events.get(0).phase());
        assertEquals("fallback", events.get(1).phase());
        assertTrue(events.get(1).fallback());
        verify(fixture.counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message");
        verify(fixture.counselingApp).doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id");
        verify(fixture.counselingApp, never()).doChatWithAgentContextByStreamPrepared(
                anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void successfulAgentPlansRetrievesGradesThenStreamsDeepAnswer() {
        Fixture fixture = new Fixture();
        when(fixture.historyService.getRecentMessages("chat-id", fixture.properties.getHistoryMessages()))
                .thenReturn(List.of());
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(
                response("""
                        {"shouldRetrieve":true,"stage":"clarification","focus":"作业延期后的焦虑",
                         "retrievalQueries":["学业延期 焦虑 现实影响"],"missingInformation":["延期的具体原因"]}
                        """),
                response("""
                        {"selectedIds":["C1"],"evidenceGaps":["当前睡眠影响"]}
                        """)
        );
        Document document = Document.builder()
                .id("case-1")
                .text("案例编号 2026-07-18-call-07 学业压力案例摘要")
                .metadata("title", "学业压力")
                .score(0.82)
                .build();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(fixture.transcriptSearchService.search(
                eq("2026-07-18-call-07"), anyString(), eq(fixture.properties.getTranscriptSnippetsPerCase())))
                .thenReturn(java.util.Optional.empty());
        when(fixture.counselingApp.doChatWithAgentContextByStreamPrepared(
                eq(OWNER_ID), eq("message"), eq("chat-id"), anyString()))
                .thenReturn(Flux.just("deep answer", "[DONE]"));

        List<CounselingStreamEvent> events = fixture.executor()
                .stream("message", "chat-id", OWNER_ID)
                .collectList()
                .block();

        assertEquals(
                List.of("status", "status", "status", "status", "delta", "done"),
                eventTypes(events));
        assertEquals(List.of("planning", "retrieving", "grading", "answering"),
                events.subList(0, 4).stream().map(CounselingStreamEvent::phase).toList());
        assertEquals("deep", events.get(4).effectiveMode());
        assertFalse(events.get(4).fallback());
        verify(fixture.counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "message");
        verify(fixture.counselingApp).doChatWithAgentContextByStreamPrepared(
                eq(OWNER_ID), eq("message"), eq("chat-id"), anyString());
        verify(fixture.counselingApp, never()).doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id");
    }

    @Test
    void pythonWorkerCanPlanAndRefineWithoutCallingLocalPlanner() {
        Fixture fixture = new Fixture();
        when(fixture.historyService.getRecentMessages("chat-id", fixture.properties.getHistoryMessages()))
                .thenReturn(List.of());
        when(fixture.aiWorkerClient.plan(any())).thenReturn(Optional.of(
                new AiWorkerContracts.PlanResponse(
                        "1", "worker-request", "clarification", true,
                        "睡眠与学业压力", List.of("学业压力 失眠 现实影响"), List.of("持续时间"),
                        "deepseek", false, List.of(), 5, List.of())));
        when(fixture.aiWorkerClient.refine(any())).thenReturn(Optional.of(
                new AiWorkerContracts.RefineResponse(
                        "1", "worker-request",
                        List.of(new AiWorkerContracts.SelectedEvidence(
                                "C1", "2026-07-18-call-07", 0.91, List.of("vector", "bm25"),
                                List.of(new AiWorkerContracts.EvidenceSnippet(
                                        "00:01:00", "00:01:20", "来访者描述了压力对睡眠的影响。",
                                        "https://example.test/source", 91.0)))),
                        List.of("持续时间"), "rrf", false, List.of(), 8)));
        Document document = Document.builder()
                .id("case-1")
                .text("案例编号 2026-07-18-call-07 学业压力与睡眠案例摘要")
                .metadata("title", "学业压力与睡眠")
                .score(0.84)
                .build();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(fixture.counselingApp.doChatWithAgentContextByStreamPrepared(
                eq(OWNER_ID), eq("message"), eq("chat-id"), anyString()))
                .thenReturn(Flux.just("worker answer", "[DONE]"));

        List<CounselingStreamEvent> events = fixture.executor()
                .stream("message", "chat-id", OWNER_ID)
                .collectList()
                .block();

        assertEquals(List.of("status", "status", "status", "status", "delta", "done"),
                eventTypes(events));
        assertEquals("deep", events.get(4).effectiveMode());
        verify(fixture.chatModel, never()).call(any(Prompt.class));
        verify(fixture.transcriptSearchService, never()).search(anyString(), anyString(), any(Integer.class));
    }

    @Test
    void degradedPythonPlanIsHandledByTheLocalJavaPlanner() {
        Fixture fixture = new Fixture();
        when(fixture.historyService.getRecentMessages("chat-id", fixture.properties.getHistoryMessages()))
                .thenReturn(List.of());
        when(fixture.aiWorkerClient.plan(any())).thenReturn(Optional.of(
                new AiWorkerContracts.PlanResponse(
                        "1", "worker-request", "clarification", true,
                        "degraded", List.of("degraded query"), List.of(),
                        "heuristic", true, List.of("llm_unavailable"), 1, List.of())));
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"shouldRetrieve":false,"stage":"clarification","focus":"继续澄清",
                 "retrievalQueries":[],"missingInformation":["具体经过"]}
                """));
        when(fixture.counselingApp.doChatWithAgentContextByStreamPrepared(
                eq(OWNER_ID), eq("message"), eq("chat-id"), anyString()))
                .thenReturn(Flux.just("java planner answer", "[DONE]"));

        List<CounselingStreamEvent> events = fixture.executor()
                .stream("message", "chat-id", OWNER_ID)
                .collectList()
                .block();

        assertEquals("deep", events.get(4).effectiveMode());
        verify(fixture.chatModel).call(any(Prompt.class));
        verify(fixture.aiWorkerClient, never()).refine(any());
    }

    @Test
    void explicitCrisisLanguageIsRecognizedForFastPath() {
        assertTrue(SpringAiCounselingAgentExecutor.requiresImmediateSafetyResponse("我现在真的不想活了"));
        assertTrue(SpringAiCounselingAgentExecutor.requiresImmediateSafetyResponse("我决定了,这周就自杀"));
        assertTrue(SpringAiCounselingAgentExecutor.requiresImmediateSafetyResponse("真的活不下去了"));
        assertTrue(SpringAiCounselingAgentExecutor.requiresImmediateSafetyResponse("死了算了"));
        assertTrue(SpringAiCounselingAgentExecutor.requiresImmediateSafetyResponse("我吞了一整瓶药"));
        assertFalse(SpringAiCounselingAgentExecutor.requiresImmediateSafetyResponse("我最近只是作业有点拖延"));
    }

    @Test
    void timedOutVectorWorkIsCancelledBeforeFallback() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.setStepTimeoutSeconds(1);
        when(fixture.historyService.getRecentMessages("chat-id", fixture.properties.getHistoryMessages()))
                .thenReturn(List.of());
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"shouldRetrieve":true,"stage":"clarification","focus":"超时测试",
                 "retrievalQueries":["阻塞查询"],"missingInformation":[]}
                """));
        CountDownLatch interrupted = new CountDownLatch(1);
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        when(fixture.counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "message", "chat-id"))
                .thenReturn(Flux.just("fallback answer", "[DONE]"));

        List<CounselingStreamEvent> events = fixture.executor()
                .stream("message", "chat-id", OWNER_ID)
                .collectList()
                .block();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("status", "status", "fallback", "delta", "done"), eventTypes(events));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static List<String> eventTypes(List<CounselingStreamEvent> events) {
        return events.stream().map(CounselingStreamEvent::type).toList();
    }

    private static final class Fixture {
        private final CounselingApp counselingApp = mock(CounselingApp.class);
        private final ConversationHistoryService historyService = mock(ConversationHistoryService.class);
        private final TranscriptSearchService transcriptSearchService = mock(TranscriptSearchService.class);
        private final VectorStore vectorStore = mock(VectorStore.class);
        private final ChatModel chatModel = mock(ChatModel.class);
        private final AiWorkerClient aiWorkerClient = mock(AiWorkerClient.class);
        private final ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        private final DeepThinkingProperties properties = new DeepThinkingProperties();

        private Fixture() {
            when(aiWorkerClient.plan(any())).thenReturn(Optional.empty());
            when(aiWorkerClient.refine(any())).thenReturn(Optional.empty());
        }

        private SpringAiCounselingAgentExecutor executor() {
            return new SpringAiCounselingAgentExecutor(
                    counselingApp,
                    historyService,
                    transcriptSearchService,
                    vectorStore,
                    properties,
                    chatModel,
                    aiWorkerClient,
                    memoryService,
                    AGENT_EXECUTOR,
                    AGENT_SCHEDULER
            );
        }
    }
}
