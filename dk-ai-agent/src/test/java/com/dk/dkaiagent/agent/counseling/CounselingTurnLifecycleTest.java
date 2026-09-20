package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.*;
import com.dk.dkaiagent.history.ChatTurnService;
import com.dk.dkaiagent.memory.SafetyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CounselingTurnLifecycleTest {
    CounselingTurnPipeline pipeline;
    CounselingApp app;
    ChatTurnService turns;
    @BeforeEach void setup() {
        pipeline = new CounselingTurnPipeline();
        app = mock(CounselingApp.class);
        turns = PipelineTestSupport.configure(pipeline);
        ReflectionTestUtils.setField(pipeline, "counselingApp", app);
        ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", mock(CounselingAgentExecutor.class));
        ReflectionTestUtils.setField(pipeline, "safetyProperties", new SafetyProperties());
        ReflectionTestUtils.setField(pipeline, "crisisResponse", new CrisisResponse(new SafetyProperties()));
    }
    CounselingTurnPipeline.CounselingTurnRequest request(String text) {
        return new CounselingTurnPipeline.CounselingTurnRequest(1, "chat", text, "key", false);
    }
    @Test void commitsBeforeDone() {
        List<CounselingStreamEvent> received = new ArrayList<>();
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat")).thenReturn(Flux.just("answer"));
        doAnswer(call -> { assertTrue(received.stream().noneMatch(e -> e.type().equals("done"))); return null; })
                .when(turns).finish(1, "chat", "key", "answer", "COMPLETED", "standard", false);
        pipeline.run(request("hello")).doOnNext(received::add).blockLast();
        assertEquals("done", received.getLast().type());
        verify(turns).finish(1, "chat", "key", "answer", "COMPLETED", "standard", false);
    }
    @Test void databaseFailureCannotBecomeDone() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat")).thenReturn(Flux.just("answer"));
        doThrow(new IllegalStateException("database down")).when(turns)
                .finish(anyLong(), anyString(), any(), anyString(), anyString(), anyString(), anyBoolean());
        var events = pipeline.run(request("hello")).collectList().block();
        assertTrue(events.stream().noneMatch(e -> e.type().equals("done")));
        assertEquals("error", events.getLast().type());
        assertEquals("failed", events.getLast().phase());
    }
    @Test void cancelArchivesOnlyVisiblePartialReply() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat")).thenReturn(Flux.just("partial").concatWith(Flux.never()));
        var subscription = pipeline.run(request("hello")).subscribe();
        subscription.dispose();
        verify(turns).finish(1, "chat", "key", "partial", "CANCELLED", "standard", false);
    }
    @Test void savesGuardedTextInsteadOfRejectedModelText() {
        when(app.doChatWithRagByStreamPrepared(1, "我不想活了", "chat")).thenReturn(Flux.just("尊重你的", "决定"));
        var events = pipeline.run(request("我不想活了")).collectList().block();
        String visible = events.stream().filter(e -> e.type().equals("delta")).map(CounselingStreamEvent::content).reduce("", String::concat);
        assertFalse(visible.contains("尊重你的决定"));
        verify(turns).finish(1, "chat", "key", visible, "COMPLETED", "standard", false);
    }
    @Test void completedReplayDoesNotInvokeModelOrArchiveAgain() {
        when(turns.begin(1, "chat", "key", "hello", false)).thenReturn(new ChatTurnService.Turn("key", "COMPLETED", "cached", "standard", false));
        var events = pipeline.run(request("hello")).collectList().block();
        assertEquals("cached", events.getFirst().content());
        verifyNoInteractions(app);
        verify(turns, never()).finish(anyLong(), anyString(), any(), anyString(), anyString(), anyString(), anyBoolean());
    }
    @Test void missingDoneIsAnErrorAndPreservesPartialContent() {
        var executor = (CounselingAgentExecutor) ReflectionTestUtils.getField(pipeline, "counselingAgentExecutor");
        when(executor.prepareAndAnswer("hello", "chat", 1)).thenReturn(
                Flux.just(CounselingStreamEvent.delta("partial", "deep", false)));
        var events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(1,"chat","hello","key",true)).collectList().block();
        assertEquals("error", events.getLast().type());
        verify(turns).finish(1, "chat", "key", "partial", "FAILED", "deep", false);
    }

    @Test void literalCompletionMarkerIsContentAndDoesNotTruncate() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat"))
                .thenReturn(Flux.just("前缀", "[DONE]", "后缀"));
        var events = pipeline.run(request("hello")).collectList().block();
        assertEquals(List.of("delta", "delta", "delta", "done"), events.stream().map(CounselingStreamEvent::type).toList());
        verify(turns).finish(1,"chat","key","前缀[DONE]后缀","COMPLETED","standard",false);
    }

    @Test void modelErrorCannotBeConvertedIntoNormalCompletion() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat"))
                .thenReturn(Flux.just("partial").concatWith(Flux.error(new IllegalStateException("upstream"))));
        assertEquals("error", pipeline.run(request("hello")).blockLast().type());
        verify(turns).finish(1,"chat","key","partial","FAILED","standard",false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void neutralFollowUpRetainsHistoricalOutputGuardInBothModes(boolean deep) {
        var history = mock(com.dk.dkaiagent.history.ConversationHistoryService.class);
        when(history.getRecentMessages("chat", SafetyDirectives.CONTEXT_MESSAGES)).thenReturn(List.of(
                new org.springframework.ai.chat.messages.UserMessage("我不想活了"),
                new org.springframework.ai.chat.messages.AssistantMessage("我在这里"),
                new org.springframework.ai.chat.messages.UserMessage("嗯")));
        // A fresh app instance reads persisted history; no in-memory risk flag is required.
        var freshApp = new CounselingApp(mock(org.springframework.ai.chat.model.ChatModel.class), history,
                mock(com.dk.dkaiagent.memory.ConversationMemoryService.class), 30);
        when(app.outputRiskTier("chat")).thenAnswer(call -> freshApp.outputRiskTier("chat"));
        if (deep) {
            var executor = (CounselingAgentExecutor) ReflectionTestUtils.getField(pipeline, "counselingAgentExecutor");
            when(executor.prepareAndAnswer("嗯", "chat", 1)).thenReturn(Flux.just(
                    CounselingStreamEvent.delta("尊重你的决定", "deep", false), CounselingStreamEvent.done("deep", false)));
        } else when(app.doChatWithRagByStreamPrepared(1, "嗯", "chat")).thenReturn(Flux.just("尊重你的决定"));
        var events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(1,"chat","嗯","key",deep)).collectList().block();
        String visible = events.stream().filter(e -> e.type().equals("delta")).map(CounselingStreamEvent::content).reduce("", String::concat);
        assertFalse(visible.contains("尊重你的决定"));
        assertTrue(visible.contains("12356"));
        verify(turns).finish(1,"chat","key",visible,"COMPLETED",deep ? "deep" : "standard",false);
    }
    @Test void emptyAnswerCannotCompleteSuccessfully() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat")).thenReturn(Flux.empty());
        assertEquals("error", pipeline.run(request("hello")).blockLast().type());
        verify(turns, never()).finish(anyLong(), anyString(), anyString(), anyString(), eq("COMPLETED"), anyString(), anyBoolean());
    }
    @Test void aSecondSubscriptionCannotStartAnotherGeneration() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat")).thenReturn(Flux.just("answer"));
        var stream = pipeline.run(request("hello"));
        stream.blockLast();
        assertThrows(IllegalStateException.class, stream::blockLast);
        verify(app, times(1)).doChatWithRagByStreamPrepared(1, "hello", "chat");
    }
    @Test void postCommitMaintenanceFailureDoesNotReportAnArchiveFailure() {
        when(app.doChatWithRagByStreamPrepared(1, "hello", "chat")).thenReturn(Flux.just("answer"));
        doThrow(new IllegalStateException("cleanup")).when(app).clearConversationMemory("chat");
        assertEquals("done", pipeline.run(request("hello")).blockLast().type());
    }
}
