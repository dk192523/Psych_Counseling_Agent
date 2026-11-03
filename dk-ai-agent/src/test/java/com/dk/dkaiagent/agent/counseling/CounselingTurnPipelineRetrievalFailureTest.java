package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyProperties;
import com.dk.dkaiagent.rag.KnowledgeAdmissionPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounselingTurnPipelineRetrievalFailureTest {

    @Test
    void retrievalUnavailableIsTheOnlyTerminalEventAndArchivesFailedTurn() {
        CounselingTurnPipeline pipeline = new CounselingTurnPipeline();
        var turns = PipelineTestSupport.configure(pipeline);
        var app = mock(CounselingApp.class);
        var executor = mock(CounselingAgentExecutor.class);
        var memory = mock(ConversationMemoryService.class);
        var admission = mock(KnowledgeAdmissionPolicy.class);
        var metrics = new SimpleMeterRegistry();
        var safety = new SafetyProperties();
        ReflectionTestUtils.setField(pipeline, "counselingApp", app);
        ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", executor);
        ReflectionTestUtils.setField(pipeline, "memoryService", memory);
        ReflectionTestUtils.setField(pipeline, "admissionPolicy", admission);
        ReflectionTestUtils.setField(pipeline, "metrics", metrics);
        ReflectionTestUtils.setField(pipeline, "safetyProperties", safety);
        ReflectionTestUtils.setField(pipeline, "crisisResponse", new CrisisResponse(safety));
        when(admission.insufficientAnswer("CBT 是什么")).thenReturn(Optional.empty());
        when(app.outputRiskTier("chat-id")).thenReturn(RiskTier.NONE);
        var error = CounselingStreamEvent.error("retrieval_unavailable", "知识检索暂不可用，请稍后重试。", "deep");
        when(executor.prepareAndAnswer("CBT 是什么", "chat-id", 42L)).thenReturn(Flux.just(
                CounselingStreamEvent.status("retrieving", "正在检索…"), error,
                CounselingStreamEvent.delta("故障后不得继续回答", "deep", false),
                CounselingStreamEvent.done("deep", false)));

        var events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                42L, "chat-id", "CBT 是什么", "client-msg-1", true)).collectList().block();

        assertEquals(List.of("status", "error"), events.stream().map(CounselingStreamEvent::type).toList());
        assertEquals(error, events.getLast());
        verify(turns).finish(42L, "chat-id", "client-msg-1", "", "FAILED", "deep", false);
        verify(app, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
        verify(memory, never()).onTurnArchived(anyString());
        assertEquals(1.0, metrics.get("counseling.turns").tag("outcome", "failed").counter().count());
    }
}
