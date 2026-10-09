package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.app.DesktopLlmAvailability;
import com.dk.dkaiagent.history.ChatTurnService;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import com.dk.dkaiagent.memory.SafetyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalDesktopLlmGuardTest {

    private final ChatModel model = mock(ChatModel.class);
    private final ConversationHistoryService history = mock(ConversationHistoryService.class);
    private final ConversationMemoryService memory = mock(ConversationMemoryService.class);
    private final CounselingAgentExecutor executor = mock(CounselingAgentExecutor.class);
    private CounselingTurnPipeline pipeline;
    private ChatTurnService turns;

    @BeforeEach
    void setUp() {
        CounselingApp app = new CounselingApp(model, history, memory, 30);
        clearInvocations(model); // ChatClient construction reads model defaults; no provider request.
        pipeline = new CounselingTurnPipeline();
        turns = PipelineTestSupport.configure(pipeline);
        ReflectionTestUtils.setField(pipeline, "counselingApp", app);
        ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", executor);
        ReflectionTestUtils.setField(pipeline, "memoryService", memory);
        SafetyProperties safety = new SafetyProperties();
        ReflectionTestUtils.setField(pipeline, "safetyProperties", safety);
        ReflectionTestUtils.setField(pipeline, "crisisResponse", new CrisisResponse(safety));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-desktop");
        ReflectionTestUtils.invokeMethod(pipeline, "configureDesktopLlm", new DesktopLlmAvailability(environment));
        when(history.getRecentMessages(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void standardAndDeepRequestsFailBeforeAdmissionOrAnyProviderCall() {
        for (boolean deep : List.of(false, true)) {
            List<CounselingStreamEvent> events = run("最近工作有点忙", deep);
            assertEquals(1, events.size());
            assertEquals("error", events.getFirst().type());
            assertEquals("llm_unconfigured", events.getFirst().phase());
            assertEquals(DesktopLlmAvailability.UNCONFIGURED_MESSAGE, events.getFirst().content());
        }
        verifyNoInteractions(turns, executor, memory, model);
        verify(history, never()).appendUserMessage(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void crisisTemplateStillCompletesAndArchivesWithoutCloudMemoryConsolidation() {
        List<CounselingStreamEvent> events = run("我吞了一整瓶药", true);
        assertEquals("done", events.getLast().type());
        String answer = events.stream().map(CounselingStreamEvent::content).reduce("", String::concat);
        assertTrue(answer.contains("你现在安全吗"));
        verify(turns).finish(eq(42L), eq("chat-id"), eq("client-msg"), eq(answer),
                eq("COMPLETED"), eq("standard"), eq(false));
        verify(history).appendUserMessage(42L, "chat-id", "我吞了一整瓶药", "client-msg");
        verifyNoInteractions(executor, memory);
        verify(model, never()).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void passiveRiskKeepsSafetyResourcesInTheDisabledResponse() {
        CounselingStreamEvent event = run("我不想活了", false).getFirst();
        assertEquals("llm_unconfigured", event.phase());
        assertTrue(event.content().contains("随时可以求助"));
        verifyNoInteractions(turns, executor, memory, model);
    }

    @Test
    void configuredDesktopAndServerProfilesKeepTheirExistingBehavior() {
        MockEnvironment desktop = new MockEnvironment().withProperty("app.desktop.llm-configured", "true");
        desktop.setActiveProfiles("local-desktop");
        assertFalse(new DesktopLlmAvailability(desktop).isUnconfigured());
        MockEnvironment server = new MockEnvironment().withProperty("app.desktop.llm-configured", "false");
        server.setActiveProfiles("prod");
        assertFalse(new DesktopLlmAvailability(server).isUnconfigured());
    }

    private List<CounselingStreamEvent> run(String message, boolean deep) {
        return pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                42L, "chat-id", message, "client-msg", deep)).collectList().block();
    }
}
