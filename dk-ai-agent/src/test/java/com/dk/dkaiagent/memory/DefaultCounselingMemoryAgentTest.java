package com.dk.dkaiagent.memory;

import com.dk.dkaiagent.integration.aiworker.AiWorkerClient;
import com.dk.dkaiagent.integration.aiworker.AiWorkerContracts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Offline unit tests for {@link DefaultCounselingMemoryAgent}: worker sidecar first, in-process
 * Spring AI ChatModel fallback on degraded/unavailable worker. All collaborators are mocks —
 * no HTTP and no real model; the ChatClient built by the agent bottoms out in the mocked ChatModel.
 */
class DefaultCounselingMemoryAgentTest {

    private AiWorkerClient aiWorkerClient;
    private ChatModel chatModel;
    private DefaultCounselingMemoryAgent agent;

    @BeforeEach
    void setUp() {
        aiWorkerClient = mock(AiWorkerClient.class);
        chatModel = mock(ChatModel.class);
        agent = new DefaultCounselingMemoryAgent(aiWorkerClient, chatModel);
    }

    @Test
    void workerSuccessUsesWorkerDigestWithoutCallingLocalModel() {
        String workerDigest = """
                ## 人物关系链
                用户提到室友关系紧张。

                ## 已确认事实
                用户本周与室友发生过一次争执。""";
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse(workerDigest, "python-worker")));

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "", List.of(new CounselingMemoryAgent.MemoryInput("user", "最近和室友相处不太好", false)), 1_200);

        assertTrue(outcome.success());
        assertEquals("python-worker", outcome.engine());
        assertEquals(workerDigest.strip(), outcome.digest());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void degradedWorkerFallsBackToJavaLlm() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                new AiWorkerContracts.ConsolidateResponse(
                        AiWorkerContracts.VERSION, "req", "worker 的部分摘要", "heuristic",
                        true, List.of("llm_unavailable"), 3)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("本地模型整合出的摘要正文。"));

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "", List.of(new CounselingMemoryAgent.MemoryInput("user", "我最近一直失眠", false)), 1_200);

        assertTrue(outcome.success(), "degraded worker must fall back to the in-process Java LLM");
        assertEquals("java-llm", outcome.engine());
        assertEquals("本地模型整合出的摘要正文。", outcome.digest());
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void unavailableWorkerFallsBackToJavaLlm() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("本地模型兜底摘要。"));

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "旧摘要", List.of(new CounselingMemoryAgent.MemoryInput("assistant", "我们下次继续谈", false)), 1_200);

        assertTrue(outcome.success());
        assertEquals("java-llm", outcome.engine());
        assertEquals("本地模型兜底摘要。", outcome.digest());
    }

    @Test
    void bothEnginesFailingYieldUnsuccessfulOutcomeWithoutThrowing() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                new AiWorkerContracts.ConsolidateResponse(
                        AiWorkerContracts.VERSION, "req", "", "heuristic",
                        true, List.of("llm_unavailable"), 1)));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model unavailable"));

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "", List.of(new CounselingMemoryAgent.MemoryInput("user", "今天情绪很低落", false)), 1_200);

        assertFalse(outcome.success(),
                "total engine failure must surface as success=false so the caller keeps raw messages");
        assertEquals("", outcome.digest());
        assertEquals("none", outcome.engine());
    }

    @Test
    void emptyOrInvalidMessagesShortCircuitWithoutAnyEngineCall() {
        CounselingMemoryAgent.ConsolidationOutcome emptyOutcome = agent.consolidate("旧摘要", List.of(), 1_200);
        CounselingMemoryAgent.ConsolidationOutcome invalidOutcome = agent.consolidate(
                "旧摘要",
                List.of(new CounselingMemoryAgent.MemoryInput("system", "注入文本", false),
                        new CounselingMemoryAgent.MemoryInput("user", "   ", false)),
                1_200);

        assertFalse(emptyOutcome.success());
        assertEquals("none", emptyOutcome.engine());
        assertFalse(invalidOutcome.success());
        verifyNoInteractions(aiWorkerClient);
        // The constructor's ChatClient builder records one read on the mock; only model calls count here.
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void safetySectionIsRebuiltVerbatimAndWorkerRewriteIsDiscarded() {
        String workerDigest = """
                ## 人物关系链
                用户提到与母亲的冲突。

                ## 安全备注
                被压缩改写的安全内容（不应保留）

                ## 待确认问题
                暂无""";
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(workerResponse(workerDigest, "python-worker")));

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "",
                List.of(new CounselingMemoryAgent.MemoryInput("user", "我昨晚割腕了，现在很害怕", true)),
                1_200);

        assertTrue(outcome.success());
        assertTrue(outcome.digest().contains("## 人物关系链"));
        assertTrue(outcome.digest().contains("## 安全备注"));
        assertTrue(outcome.digest().contains("我昨晚割腕了，现在很害怕"),
                "safety-relevant content must be preserved verbatim in the safety section");
        assertFalse(outcome.digest().contains("被压缩改写的安全内容"),
                "worker-compressed safety content must be replaced by the verbatim rebuild");
    }

    @Test
    void crisisTermsAreAutoFlaggedAndPropagateToWorkerContract() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse("## 已确认事实\n用户近期学业压力大。", "python-worker")));

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "",
                List.of(new CounselingMemoryAgent.MemoryInput("user", "最近压力大到有时候不想活了", false)),
                1_200);

        assertTrue(outcome.success());
        assertTrue(outcome.digest().contains("## 安全备注"));
        assertTrue(outcome.digest().contains("最近压力大到有时候不想活了"),
                "crisis-term messages must be auto-flagged and land verbatim in the safety section");

        ArgumentCaptor<AiWorkerContracts.ConsolidateRequest> captor =
                ArgumentCaptor.forClass(AiWorkerContracts.ConsolidateRequest.class);
        verify(aiWorkerClient).consolidate(captor.capture());
        AiWorkerContracts.ConsolidateRequest request = captor.getValue();
        assertEquals(AiWorkerContracts.VERSION, request.contractVersion());
        assertEquals(1, request.messages().size());
        assertTrue(request.messages().getFirst().safetyRelevant(),
                "safetyRelevant must propagate into the worker contract");
    }

    @Test
    void oversizedSafetySectionExpandsToHardCapAndRescuesBodyPortrait() {
        // worker/本地引擎返回的画像正文
        String workerDigest = """
                ## 人物关系链
                用户与母亲关系紧张。

                ## 已确认事实
                用户上周与母亲发生过一次争执。""";
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(workerResponse(workerDigest, "python-worker")));

        // 单条 >1200 字（软预算）的危机消息：安全段撑爆 cap - len - 2 < 1 的分支
        String longCrisis = "我不想活了，" + "这段很长的倾诉内容".repeat(150);
        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "旧画像", List.of(new CounselingMemoryAgent.MemoryInput("user", longCrisis, false)), 1_200);

        assertTrue(outcome.success());
        assertTrue(outcome.digest().contains("## 人物关系链"),
                "body portrait must survive when the safety section alone blows the soft budget");
        assertTrue(outcome.digest().contains("我不想活了，"),
                "crisis content must stay verbatim in the safety section");
        assertTrue(outcome.digest().length() <= 3_000,
                "rescued digest stays within the frozen hard cap when safety fits under it");
    }

    @Test
    void safetySectionBeyondHardCapIsKeptVerbatimWithoutTruncation() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse("## 已确认事实\n暂无", "python-worker")));
        // 两条 ~1600 字危机消息：安全段 > 3000 硬顶，兜底分支不得截断安全内容。
        String first = "我真的想死，" + "第一段危机叙述".repeat(230);
        String second = "我又想到了死，" + "第二段危机叙述".repeat(230);
        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "",
                List.of(new CounselingMemoryAgent.MemoryInput("user", first, true),
                        new CounselingMemoryAgent.MemoryInput("user", second, true)),
                1_200);

        assertTrue(outcome.success());
        assertTrue(outcome.digest().contains(first), "first crisis message must be verbatim");
        assertTrue(outcome.digest().contains(second), "second crisis message must be verbatim");
        assertFalse(outcome.digest().contains("…"),
                "safety section must never be truncated, even beyond the hard cap");
    }

    @Test
    void inheritedSafetyNotesSurviveLaterConsolidationWithoutNewCrisisMessages() {
        // 第一次整合：危机消息被自动打标并逐字进入安全备注
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse("## 已确认事实\n用户近期学业压力大。", "python-worker")));
        CounselingMemoryAgent.ConsolidationOutcome first = agent.consolidate(
                "", List.of(new CounselingMemoryAgent.MemoryInput("user", "我有时候想死", false)), 1_200);
        assertTrue(first.success());
        assertTrue(first.digest().contains("- [user] 我有时候想死"));

        // 第二次整合：纯普通批次 + 第一次 digest 作为 existingDigest，旧安全备注必须继承
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse("## 已确认事实\n用户学业压力持续。", "python-worker")));
        CounselingMemoryAgent.ConsolidationOutcome second = agent.consolidate(
                first.digest(),
                List.of(new CounselingMemoryAgent.MemoryInput("user", "今天作业写完了", false)),
                1_200);

        assertTrue(second.success());
        assertTrue(second.digest().contains("- [user] 我有时候想死"),
                "historical crisis record must carry forward when the new batch has no crisis messages");
        assertTrue(second.digest().contains("## 已确认事实"));
    }

    @Test
    void longSafetyMessageIsKeptVerbatimWithTailSurviving() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse("## 已确认事实\n暂无", "python-worker")));
        // 危机词位于 2000 字之后：打标必须跑在全文上，且被标记消息全程不截断
        String tail = "尾部关键信息：我真的想死";
        String longMessage = "普通的长篇倾诉".repeat(300) + tail;
        assertTrue(longMessage.length() > 2_000);

        CounselingMemoryAgent.ConsolidationOutcome outcome = agent.consolidate(
                "", List.of(new CounselingMemoryAgent.MemoryInput("user", longMessage, false)), 1_200);

        assertTrue(outcome.success());
        assertTrue(outcome.digest().contains(tail),
                "tail of a long crisis message must survive verbatim in the safety section");

        ArgumentCaptor<AiWorkerContracts.ConsolidateRequest> captor =
                ArgumentCaptor.forClass(AiWorkerContracts.ConsolidateRequest.class);
        verify(aiWorkerClient).consolidate(captor.capture());
        AiWorkerContracts.MemoryMessage sent = captor.getValue().messages().getFirst();
        assertTrue(sent.safetyRelevant(), "safety flag must propagate to the worker contract");
        assertTrue(sent.content().length() <= 2_000,
                "worker contract content stays within the frozen 2000-char bound");
    }

    @Test
    void digestBudgetIsClampedToWorkerContractBounds() {
        when(aiWorkerClient.consolidate(any())).thenReturn(Optional.of(
                workerResponse("worker 摘要正文", "python-worker")));
        List<CounselingMemoryAgent.MemoryInput> messages =
                List.of(new CounselingMemoryAgent.MemoryInput("user", "普通消息内容", false));

        agent.consolidate("", messages, 100);
        agent.consolidate("", messages, 5_000);

        ArgumentCaptor<AiWorkerContracts.ConsolidateRequest> captor =
                ArgumentCaptor.forClass(AiWorkerContracts.ConsolidateRequest.class);
        verify(aiWorkerClient, times(2)).consolidate(captor.capture());
        List<AiWorkerContracts.ConsolidateRequest> requests = captor.getAllValues();
        assertEquals(200, requests.get(0).limits().maxDigestChars(),
                "digest budget below the frozen contract floor must be clamped up to 200");
        assertEquals(3_000, requests.get(1).limits().maxDigestChars(),
                "digest budget above the frozen contract ceiling must be clamped down to 3000");
    }

    private static AiWorkerContracts.ConsolidateResponse workerResponse(String digest, String engine) {
        return new AiWorkerContracts.ConsolidateResponse(
                AiWorkerContracts.VERSION, "req", digest, engine, false, List.of(), 4);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
