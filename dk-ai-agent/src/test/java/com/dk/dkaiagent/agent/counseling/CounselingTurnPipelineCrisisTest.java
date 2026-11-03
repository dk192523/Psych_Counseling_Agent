package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;

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

/**
 * 危机拦截测试：IMMINENT 级消息必须在 pipeline 层被拦下，走专用模板，
 * 绝不进入任何 LLM 聊天链——这是快速模式此前缺失的危机前置检查。
 */
class CounselingTurnPipelineCrisisTest {

    private static final long OWNER_ID = 42L;

    private CounselingApp counselingApp;
    private CounselingAgentExecutor executor;
    private CounselingTurnPipeline pipeline;

    @BeforeEach
    void setUp() {
        counselingApp = mock(CounselingApp.class);
        executor = mock(CounselingAgentExecutor.class);
        pipeline = new CounselingTurnPipeline();
        com.dk.dkaiagent.agent.counseling.PipelineTestSupport.configure(pipeline);
        Mockito.lenient().when(counselingApp.doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString()))
                .thenReturn(Flux.just("普通回答"));
        ReflectionSupport.setField(pipeline, "counselingApp", counselingApp);
        ReflectionSupport.setField(pipeline, "counselingAgentExecutor", executor);
        ReflectionSupport.setField(pipeline, "crisisResponse", new CrisisResponse(new SafetyProperties()));
        ReflectionSupport.setField(pipeline, "safetyProperties", new SafetyProperties());
    }

    private static final class ReflectionSupport {
        static void setField(Object target, String name, Object value) {
            try {
                var field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void imminentRiskBypassesAllLlmChainsAndUsesCrisisTemplate() {
        List<CounselingStreamEvent> events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                OWNER_ID, "chat-id", "我吞了一整瓶药", "client-msg-1", true))
                .collectList()
                .block();

        // 用户消息与模板回答都归档
        verify(counselingApp).prepareConversationTurn(OWNER_ID, "chat-id", "我吞了一整瓶药", "client-msg-1");
        verify(counselingApp, never()).archiveAssistantAnswer(anyLong(), anyString(), anyString());
        // 不进入任何 LLM 链——深度模式开关形同虚设，这正是拦截的意义
        verify(executor, never()).prepareAndAnswer(anyString(), anyString(), anyLong());
        verify(counselingApp, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());

        String joined = events.stream()
                .map(CounselingStreamEvent::content)
                .reduce("", String::concat);
        assertTrue(joined.contains("你现在安全吗"));
        assertTrue(joined.contains("12356"));
        // 标准事件契约：delta…done，前端零改动
        assertEquals("delta", events.get(0).type());
        assertEquals("done", events.get(events.size() - 1).type());
        assertFalse(events.get(events.size() - 1).fallback());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interpersonalDangerUsesVictimInstructionsInBothModes(boolean deepThinking) {
        List<CounselingStreamEvent> events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                OWNER_ID, "chat-id", "他现在拿刀堵着我", null, deepThinking)).collectList().block();
        String text = events.stream().map(CounselingStreamEvent::content).reduce("", String::concat);

        assertTrue(text.contains("不要争辩、夺取武器"));
        assertTrue(text.contains("当地报警或紧急服务"));
        assertFalse(text.contains("伤害自己"));
        assertFalse(text.contains("那些念头"));
        assertEquals("done", events.get(events.size() - 1).type());
        verify(executor, never()).prepareAndAnswer(anyString(), anyString(), anyLong());
        verify(counselingApp, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void harmToOthersUsesDeescalationInstructionsInBothModes(boolean deepThinking) {
        List<CounselingStreamEvent> events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                OWNER_ID, "chat-id", "我现在要拿刀杀了他", null, deepThinking)).collectList().block();
        String text = events.stream().map(CounselingStreamEvent::content).reduce("", String::concat);

        assertTrue(text.contains("暂停接近对方"));
        assertTrue(text.contains("与危险物和对方保持距离"));
        assertTrue(text.contains("寻找线下帮助"));
        assertFalse(text.contains("伤害自己"));
        assertEquals("done", events.get(events.size() - 1).type());
        verify(executor, never()).prepareAndAnswer(anyString(), anyString(), anyLong());
        verify(counselingApp, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
    }

    @Test
    void outputSupplementUsesVictimInstructionsWhenCrisisTemplateIsDisabled() {
        SafetyProperties safety = new SafetyProperties();
        safety.setCrisisResponseEnabled(false);
        ReflectionSupport.setField(pipeline, "safetyProperties", safety);

        List<CounselingStreamEvent> events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                OWNER_ID, "chat-id", "他现在拿刀堵着我", null, false)).collectList().block();
        String text = events.stream().map(CounselingStreamEvent::content).reduce("", String::concat);

        verify(counselingApp).doChatWithRagByStreamPrepared(OWNER_ID, "他现在拿刀堵着我", "chat-id");
        assertTrue(text.contains("当地报警或紧急服务"));
        assertTrue(text.contains("不要争辩、夺取武器"));
        assertFalse(text.contains("伤害自己"));
        assertFalse(text.contains("那些念头"));
    }

    @Test
    void passiveRiskContinuesThroughLlmChainWithGuardArmed() {
        // PASSIVE 不切换链路：照常走 LLM，但输出侧检查已武装（模型应和自伤时补资源）。
        when(counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "我不想活了", "chat-id"))
                .thenReturn(Flux.just("尊重你的决定"));

        List<CounselingStreamEvent> events = pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                OWNER_ID, "chat-id", "我不想活了", null, false))
                .collectList()
                .block();

        verify(executor, never()).prepareAndAnswer(anyString(), anyString(), anyLong());
        // PASSIVE 轮回复没有资源 → 输出检查兜底补上资源提示（fallback=true 标记），再收 done
        String joined = events.stream()
                .map(CounselingStreamEvent::content)
                .reduce("", String::concat);
        assertTrue(joined.contains("随时可以求助"));
        assertTrue(joined.contains("12356"));
        assertEquals("done", events.get(events.size() - 1).type());
        assertFalse(joined.contains("尊重你的决定"));
        assertFalse(events.get(events.size() - 1).fallback());
    }

    @Test
    void normalMessageIsUnaffected() {
        pipeline.run(new CounselingTurnPipeline.CounselingTurnRequest(
                OWNER_ID, "chat-id", "最近工作有点忙", null, false))
                .collectList()
                .block();

        verify(counselingApp).doChatWithRagByStreamPrepared(OWNER_ID, "最近工作有点忙", "chat-id");
        verify(counselingApp, never()).archiveAssistantAnswer(anyLong(), anyString(), anyString());
    }
}
