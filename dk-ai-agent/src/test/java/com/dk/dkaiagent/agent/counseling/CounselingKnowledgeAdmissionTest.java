package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import com.dk.dkaiagent.memory.SafetyProperties;
import com.dk.dkaiagent.rag.KnowledgeAdmissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CounselingKnowledgeAdmissionTest {
    @Test void bothModesCompleteAndArchiveSameInsufficiencyWithoutAnswerOrMemoryModels() {
        for (boolean deep : List.of(false, true)) {
            var pipeline = new CounselingTurnPipeline();
            var turns = PipelineTestSupport.configure(pipeline);
            var app = mock(CounselingApp.class);
            var executor = mock(CounselingAgentExecutor.class);
            var memory = mock(ConversationMemoryService.class);
            ReflectionTestUtils.setField(pipeline, "counselingApp", app);
            ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", executor);
            ReflectionTestUtils.setField(pipeline, "memoryService", memory);
            ReflectionTestUtils.setField(pipeline, "safetyProperties", new SafetyProperties());
            // The deterministic explanation also completes when the desktop has no cloud API key.
            ReflectionTestUtils.setField(pipeline, "desktopLlmUnconfigured", true);
            var events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                    42L, "chat", "CBT 是什么", "msg", deep)).collectList().block();
            assertEquals(List.of("delta", "done"), events.stream().map(CounselingStreamEvent::type).toList());
            assertEquals(KnowledgeAdmissionPolicy.INSUFFICIENT_MESSAGE, events.getFirst().content());
            verify(turns).finish(42L, "chat", "msg", KnowledgeAdmissionPolicy.INSUFFICIENT_MESSAGE,
                    "COMPLETED", deep ? "deep" : "standard", false);
            verify(app, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
            verifyNoInteractions(executor, memory);
        }
    }

    @Test void emptyKnowledgeLeavesVentingAndCrisisPathsAvailable() {
        var pipeline = new CounselingTurnPipeline();
        PipelineTestSupport.configure(pipeline);
        var app = mock(CounselingApp.class);
        var executor = mock(CounselingAgentExecutor.class);
        var safety = new SafetyProperties();
        ReflectionTestUtils.setField(pipeline, "counselingApp", app);
        ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", executor);
        ReflectionTestUtils.setField(pipeline, "safetyProperties", safety);
        ReflectionTestUtils.setField(pipeline, "crisisResponse", new CrisisResponse(safety));
        when(app.doChatWithRagByStreamPrepared(42L, "我很焦虑，只想倾诉", "chat"))
                .thenReturn(Flux.just("我在听，你可以慢慢说。"));
        var vent = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                42L, "chat", "我很焦虑，只想倾诉", "vent", false)).collectList().block();
        assertEquals("我在听，你可以慢慢说。", vent.getFirst().content());
        var crisis = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                42L, "chat", "我吞了一整瓶药", "crisis", true)).collectList().block();
        assertTrue(crisis.stream().map(CounselingStreamEvent::content).reduce("", String::concat).contains("你现在安全吗"));
        assertEquals("done", crisis.getLast().type());
        verifyNoInteractions(executor);
    }
}
