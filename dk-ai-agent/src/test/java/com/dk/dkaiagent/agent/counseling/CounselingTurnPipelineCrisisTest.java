package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        Mockito.lenient().when(counselingApp.doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString()))
                .thenReturn(Flux.just("普通回答", "[DONE]"));
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
        verify(counselingApp).archiveAssistantAnswer(eq(OWNER_ID), eq("chat-id"), anyString());
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

    @Test
    void passiveRiskContinuesThroughLlmChainWithGuardArmed() {
        // PASSIVE 不切换链路：照常走 LLM，但输出侧检查已武装（模型应和自伤时补资源）。
        when(counselingApp.doChatWithRagByStreamPrepared(OWNER_ID, "我不想活了", "chat-id"))
                .thenReturn(Flux.just("尊重你的决定", "[DONE]"));

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
        assertTrue(events.get(events.size() - 2).fallback());
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
