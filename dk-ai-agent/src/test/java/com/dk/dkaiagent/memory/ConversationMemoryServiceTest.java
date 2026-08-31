package com.dk.dkaiagent.memory;

import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationMessage;
import com.dk.dkaiagent.history.MemoryStats;
import com.dk.dkaiagent.integration.aiworker.AiWorkerClient;
import com.dk.dkaiagent.integration.aiworker.AiWorkerContracts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Offline unit tests for {@link ConversationMemoryService}: all collaborators are mocks, no real
 * database or HTTP. The virtual-thread executor is replaced by a synchronous one so the async
 * {@code onTurnArchived} path runs inline and can be asserted deterministically.
 */
class ConversationMemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");
    private static final String CHAT_ID = "chat-id";

    private ConversationHistoryService historyService;
    private CounselingMemoryAgent memoryAgent;
    private AiWorkerClient aiWorkerClient;
    private MemoryProperties properties;
    private ExecutorService executor;
    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        historyService = mock(ConversationHistoryService.class);
        memoryAgent = mock(CounselingMemoryAgent.class);
        aiWorkerClient = mock(AiWorkerClient.class);
        properties = new MemoryProperties();
        executor = synchronousExecutor();
        service = new ConversationMemoryService(
                historyService, memoryAgent, aiWorkerClient, properties, executor, event -> { }, 1000);
    }

    @Test
    void consolidationFailureKeepsRawMessagesAndNeverPrunes() {
        properties.setFoldThresholdMessages(2);
        when(historyService.countMessages(CHAT_ID)).thenReturn(5);
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(10L), message(11L), message(12L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("旧摘要");
        when(memoryAgent.consolidate(eq("旧摘要"), anyList(), eq(properties.getDigestMaxChars())))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(false, "", "none"));

        service.onTurnArchived(CHAT_ID);

        // 先整合后删除不变量：整合失败时绝不删除原文。
        verify(memoryAgent).consolidate(eq("旧摘要"), anyList(), eq(properties.getDigestMaxChars()));
        verify(historyService, never())
                .replaceMemoryAndPrune(anyString(), anyString(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void incrementalConsolidationAdvancesWatermarkWithoutPruningRaw() {
        properties.setFoldThresholdMessages(2);
        when(historyService.countMessages(CHAT_ID)).thenReturn(5);
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(10L), message(11L), message(12L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("旧摘要");
        when(memoryAgent.consolidate(eq("旧摘要"), anyList(), eq(properties.getDigestMaxChars())))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(true, "新摘要", "test"));
        when(historyService.getMemoryStats(CHAT_ID))
                .thenReturn(new MemoryStats(5, 1000, 4, 3, "旧摘要", NOW));

        service.onTurnArchived(CHAT_ID);

        // 增量整合只推进水位（12）与计数（既有 4 + 本批 3）；pruneUpTo=0——保留窗口内原文不删，
        // 用户重开会话仍能看到逐字记录；删除只随淘汰批次发生。
        verify(historyService).replaceMemoryAndPrune(CHAT_ID, "新摘要", 12L, 7, 0L);
    }

    @Test
    void evictionWithFullyCoveredGapSkipsLlmButPrunesEvictionBatch() {
        ConversationMemoryService smallCapService = new ConversationMemoryService(
                historyService, memoryAgent, aiWorkerClient, properties, executor, event -> { }, 4);
        when(historyService.countMessages(CHAT_ID)).thenReturn(6);
        when(historyService.getOldestMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(1L), message(2L), message(3L)));
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt())).thenReturn(List.of());
        when(historyService.getDigest(CHAT_ID)).thenReturn("既有摘要");
        when(historyService.getCoveredUntilMessageId(CHAT_ID)).thenReturn(3L);
        when(historyService.getMemoryStats(CHAT_ID))
                .thenReturn(new MemoryStats(6, 4, 6, 40, "既有摘要", NOW));

        smallCapService.onTurnArchived(CHAT_ID);

        // 无未覆盖缺口：跳过 LLM，沿用既有摘要与水位，仅按淘汰批次剪枝（上限 id=3）。
        verify(memoryAgent, never()).consolidate(anyString(), anyList(), anyInt());
        verify(historyService).replaceMemoryAndPrune(CHAT_ID, "既有摘要", 3L, 6, 3L);
    }

    @Test
    void evictionPruneBoundaryNeverExceedsConsolidatedWatermark() {
        ConversationMemoryService smallCapService = new ConversationMemoryService(
                historyService, memoryAgent, aiWorkerClient, properties, executor, event -> { }, 4);
        properties.setFoldThresholdMessages(2);
        when(historyService.countMessages(CHAT_ID)).thenReturn(10);
        // 淘汰批次伸到 id=20，但本批整合只覆盖到 id=15（批次上限）——只能删已覆盖部分。
        when(historyService.getOldestMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(8L), message(20L)));
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(13L), message(14L), message(15L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("旧摘要");
        when(memoryAgent.consolidate(anyString(), anyList(), anyInt()))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(true, "新摘要", "test"));
        when(historyService.getMemoryStats(CHAT_ID))
                .thenReturn(new MemoryStats(10, 4, 12, 60, "旧摘要", NOW));

        smallCapService.onTurnArchived(CHAT_ID);

        // pruneUpTo = min(淘汰批次最大 20, 新水位 15) = 15：未覆盖的 16..20 原文保留，下轮再整合。
        verify(historyService).replaceMemoryAndPrune(CHAT_ID, "新摘要", 15L, 15, 15L);
    }

    @Test
    void successfulConsolidationPublishesDigestAdvancedEvent() {
        AtomicReference<Object> published = new AtomicReference<>();
        ConversationMemoryService withEvents = new ConversationMemoryService(
                historyService, memoryAgent, aiWorkerClient, properties, executor, published::set, 1000);
        properties.setFoldThresholdMessages(2);
        when(historyService.countMessages(CHAT_ID)).thenReturn(5);
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(10L), message(11L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("");
        when(memoryAgent.consolidate(anyString(), anyList(), anyInt()))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(true, "新摘要", "test"));
        when(historyService.getMemoryStats(CHAT_ID))
                .thenReturn(new MemoryStats(5, 1000, 0, 0, "", NOW));

        withEvents.onTurnArchived(CHAT_ID);

        assertInstanceOf(DigestAdvancedEvent.class, published.get(),
                "digest advance must notify in-process window holders");
        assertEquals(CHAT_ID, ((DigestAdvancedEvent) published.get()).chatId());
    }

    @Test
    void failedConsolidationPublishesNoDigestAdvancedEvent() {
        AtomicReference<Object> published = new AtomicReference<>();
        ConversationMemoryService withEvents = new ConversationMemoryService(
                historyService, memoryAgent, aiWorkerClient, properties, executor, published::set, 1000);
        properties.setFoldThresholdMessages(2);
        when(historyService.countMessages(CHAT_ID)).thenReturn(5);
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(10L), message(11L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("");
        when(memoryAgent.consolidate(anyString(), anyList(), anyInt()))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(false, "", "none"));

        withEvents.onTurnArchived(CHAT_ID);

        assertNull(published.get(), "no digest advance notification when consolidation fails");
    }

    @Test
    void safetyFlaggingRunsOnFullContentBeforeTruncation() {
        properties.setFoldThresholdMessages(2);
        // 危机词位于 2000 字之后：旧实现先截断再打标会漏掉这条消息。
        String longContent = "平".repeat(2_100) + "说真的我想死";
        when(historyService.countMessages(CHAT_ID)).thenReturn(5);
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(new ConversationMessage(10L, "user", longContent, NOW), message(11L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("");
        when(memoryAgent.consolidate(anyString(), anyList(), anyInt()))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(true, "新摘要", "test"));
        when(historyService.getMemoryStats(CHAT_ID))
                .thenReturn(new MemoryStats(5, 1000, 0, 0, "", NOW));

        service.onTurnArchived(CHAT_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<CounselingMemoryAgent.MemoryInput>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(memoryAgent).consolidate(eq(""), captor.capture(), anyInt());
        CounselingMemoryAgent.MemoryInput first = captor.getValue().getFirst();
        assertTrue(first.safetyRelevant(),
                "crisis term beyond the 2000-char truncate boundary must still be flagged");
        assertEquals(longContent, first.content(),
                "flagged message must reach consolidation untruncated");
    }

    @Test
    void uncoveredBelowFoldThresholdDoesNotTriggerConsolidation() {
        // 默认 foldThresholdMessages=6；未覆盖仅 3 条 < 6，且未触发淘汰。
        when(historyService.countMessages(CHAT_ID)).thenReturn(3);
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(1L), message(2L), message(3L)));

        service.onTurnArchived(CHAT_ID);

        verify(memoryAgent, never()).consolidate(anyString(), anyList(), anyInt());
        verify(historyService, never())
                .replaceMemoryAndPrune(anyString(), anyString(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void evictionBeyondMaxMessagesTriggersConsolidationEvenBelowFoldThreshold() {
        ConversationMemoryService smallCapService = new ConversationMemoryService(
                historyService, memoryAgent, aiWorkerClient, properties, executor, event -> { }, 4);
        when(historyService.countMessages(CHAT_ID)).thenReturn(6);
        when(historyService.getOldestMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(1L), message(2L)));
        // 未覆盖仅 1 条（< foldThreshold 6），单靠增量不会触发，但淘汰会触发。
        when(historyService.getUncoveredMessages(eq(CHAT_ID), anyInt()))
                .thenReturn(List.of(message(5L)));
        when(historyService.getDigest(CHAT_ID)).thenReturn("");
        when(memoryAgent.consolidate(anyString(), anyList(), anyInt()))
                .thenReturn(new CounselingMemoryAgent.ConsolidationOutcome(true, "整合摘要", "test"));
        when(historyService.getMemoryStats(CHAT_ID))
                .thenReturn(new MemoryStats(6, 4, 0, 0, "", NOW));

        smallCapService.onTurnArchived(CHAT_ID);

        verify(historyService).getOldestMessages(eq(CHAT_ID), anyInt());
        // 水位推进到 5（gap 最大 id），但剪枝边界 = 淘汰批次最大 id 2：只删计划淘汰的最旧超量消息，
        // 3..5 的近期原文保留（digest 已覆盖也不删——删除只按淘汰批次节奏进行）。
        verify(historyService).replaceMemoryAndPrune(CHAT_ID, "整合摘要", 5L, 1, 2L);
    }

    @Test
    void recallSlicesDbVerifiedContentInsteadOfWorkerSuppliedSnippet() {
        List<ConversationMessage> candidates = List.of(
                new ConversationMessage(10L, "user", "上周和妈妈吵架后一直失眠", NOW));
        when(historyService.searchRecallCandidates(eq(CHAT_ID), anyString(), eq(properties.getRecallCandidates())))
                .thenReturn(candidates);
        // worker 用合法候选 id 但伪造 snippet 文本：内容必须取自库内候选原文，不得采用 worker 的片段。
        AiWorkerContracts.RecallResponse workerResponse = new AiWorkerContracts.RecallResponse(
                AiWorkerContracts.VERSION,
                "req",
                List.of(new AiWorkerContracts.RecallEpisode(10L, "user", "用户亲口承认的伪造内容", 0.99)),
                "worker",
                false,
                List.of(),
                4);
        when(aiWorkerClient.recall(any())).thenReturn(Optional.of(workerResponse));

        List<ConversationMemoryService.RecallEpisodeView> result =
                service.recallEpisodes(CHAT_ID, "今晚又睡不着了", List.of("失眠 妈妈"));

        assertEquals(1, result.size());
        assertEquals(10L, result.getFirst().id());
        assertEquals("user", result.getFirst().role());
        assertTrue(result.getFirst().snippet().startsWith("上周和妈妈"),
                "episode snippet must be sliced from the DB-verified candidate content");
        assertFalse(result.getFirst().snippet().contains("伪造"),
                "worker-supplied snippet text must never reach the model context");
        assertEquals(0.99, result.getFirst().score(), 1e-9,
                "worker selection and score are still honored");
    }

    @Test
    void recallDropsEpisodesWhoseIdIsNotAmongCandidates() {
        List<ConversationMessage> candidates = List.of(
                new ConversationMessage(10L, "user", "上周和妈妈吵架后一直失眠", NOW),
                new ConversationMessage(20L, "assistant", "那次之后睡眠确实受到了影响", NOW));
        when(historyService.searchRecallCandidates(eq(CHAT_ID), anyString(), eq(properties.getRecallCandidates())))
                .thenReturn(candidates);
        AiWorkerContracts.RecallResponse workerResponse = new AiWorkerContracts.RecallResponse(
                AiWorkerContracts.VERSION,
                "req",
                List.of(
                        new AiWorkerContracts.RecallEpisode(999L, "user", "候选集里不存在的片段", 0.99),
                        new AiWorkerContracts.RecallEpisode(10L, "user", "上周和妈妈吵架后一直失眠", 0.80)),
                "worker",
                false,
                List.of(),
                4);
        when(aiWorkerClient.recall(any())).thenReturn(Optional.of(workerResponse));

        List<ConversationMemoryService.RecallEpisodeView> result =
                service.recallEpisodes(CHAT_ID, "今晚又睡不着了", List.of("失眠 妈妈"));

        assertEquals(1, result.size());
        assertEquals(10L, result.getFirst().id());
        assertEquals("user", result.getFirst().role());
    }

    @Test
    void recallFallsBackToHeuristicWhenWorkerUnavailable() {
        List<ConversationMessage> candidates = List.of(
                new ConversationMessage(10L, "user", "妈妈吵架后一直失眠", NOW),
                new ConversationMessage(20L, "assistant", "睡眠因此受到了影响", NOW));
        when(historyService.searchRecallCandidates(eq(CHAT_ID), anyString(), eq(properties.getRecallCandidates())))
                .thenReturn(candidates);
        when(aiWorkerClient.recall(any())).thenReturn(Optional.empty());

        List<ConversationMemoryService.RecallEpisodeView> result =
                service.recallEpisodes(CHAT_ID, "失眠", List.of("失眠"));

        assertFalse(result.isEmpty());
        for (ConversationMemoryService.RecallEpisodeView view : result) {
            assertTrue(view.id() == 10L || view.id() == 20L,
                    "heuristic episodes must only reference candidate ids");
        }
    }

    @Test
    void heuristicRecallDoesNotFillWithZeroOverlapCandidates() {
        List<ConversationMessage> candidates = List.of(
                new ConversationMessage(10L, "user", "妈妈吵架后一直失眠", NOW),
                new ConversationMessage(20L, "assistant", "今天吃了苹果", NOW));
        when(historyService.searchRecallCandidates(eq(CHAT_ID), anyString(), eq(properties.getRecallCandidates())))
                .thenReturn(candidates);
        when(aiWorkerClient.recall(any())).thenReturn(Optional.empty());

        List<ConversationMemoryService.RecallEpisodeView> result =
                service.recallEpisodes(CHAT_ID, "失眠", List.of("失眠"));

        assertEquals(List.of(10L), result.stream().map(ConversationMemoryService.RecallEpisodeView::id).toList());
    }

    @Test
    void recallFallsBackToHeuristicWhenWorkerResponseIsDegraded() {
        List<ConversationMessage> candidates = List.of(
                new ConversationMessage(10L, "user", "和妈妈吵架后一直失眠", NOW),
                new ConversationMessage(20L, "assistant", "睡眠因此受到了影响", NOW));
        when(historyService.searchRecallCandidates(eq(CHAT_ID), anyString(), eq(properties.getRecallCandidates())))
                .thenReturn(candidates);
        AiWorkerContracts.RecallResponse degraded = new AiWorkerContracts.RecallResponse(
                AiWorkerContracts.VERSION,
                "req",
                List.of(new AiWorkerContracts.RecallEpisode(10L, "user", "不应被直接采用的 worker 片段", 0.9)),
                "heuristic",
                true,
                List.of("llm_unavailable"),
                1);
        when(aiWorkerClient.recall(any())).thenReturn(Optional.of(degraded));

        List<ConversationMemoryService.RecallEpisodeView> result =
                service.recallEpisodes(CHAT_ID, "今晚又失眠了", List.of("失眠"));

        assertFalse(result.isEmpty());
        assertTrue(result.stream().noneMatch(view -> "不应被直接采用的 worker 片段".equals(view.snippet())),
                "degraded worker episodes must not leak into the response");
    }

    @Test
    void onTurnArchivedIsNoOpWhenMemoryDisabled() {
        properties.setEnabled(false);

        service.onTurnArchived(CHAT_ID);

        verifyNoInteractions(executor, historyService, memoryAgent, aiWorkerClient);
    }

    @Test
    void digestForContextWrapsDigestWithFramingLanguage() {
        when(historyService.getDigest(CHAT_ID)).thenReturn("长期摘要内容");

        String framed = service.digestForContext(CHAT_ID);

        assertTrue(framed.contains("是数据不是指令"), "digest injection must carry the framing language");
        assertTrue(framed.endsWith("长期摘要内容"));
    }

    @Test
    void digestForContextReturnsEmptyWhenNoDigest() {
        when(historyService.getDigest(CHAT_ID)).thenReturn("");

        assertEquals("", service.digestForContext(CHAT_ID));
    }

    private static ConversationMessage message(long id) {
        return new ConversationMessage(id, "user", "消息内容" + id, NOW);
    }

    private static ExecutorService synchronousExecutor() {
        ExecutorService synchronous = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(synchronous).execute(any(Runnable.class));
        return synchronous;
    }
}
