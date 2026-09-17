package com.dk.dkaiagent.app;

import com.dk.dkaiagent.agent.counseling.CounselingStreamEvent;
import com.dk.dkaiagent.memory.RiskTier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyOutputGuardTest {

    private static final String SUPPLEMENT = "补充：你的安全最重要，请拨打 12356。";

    private Flux<CounselingStreamEvent> streamOf(String... chunks) {
        List<CounselingStreamEvent> events = new java.util.ArrayList<>();
        for (String chunk : chunks) {
            events.add(CounselingStreamEvent.delta(chunk, "standard", false));
        }
        events.add(CounselingStreamEvent.done("standard", false));
        return Flux.fromIterable(events);
    }

    private String joined(List<CounselingStreamEvent> events) {
        return events.stream().map(CounselingStreamEvent::content).reduce("", String::concat);
    }

    @Test
    void endorsementIsReplacedBeforeAnyUnsafeTextIsExposed() {
        List<CounselingStreamEvent> events = SafetyOutputGuard.guard(
                streamOf("我理解你想结束这种痛苦"), RiskTier.PASSIVE, SUPPLEMENT)
                .collectList()
                .block();

        String text = joined(events);
        assertFalse(text.contains("我理解你想结束这种痛苦"));
        assertTrue(text.contains(SUPPLEMENT));
        // done 仍然是最后一个事件
        assertEquals("done", events.get(events.size() - 1).type());
        // 补充消息以 delta 形式插入，done 之前
        assertEquals("delta", events.get(events.size() - 2).type());
    }

    @Test
    void replyWithoutAnyResourceMarkerGetsSupplement() {
        // PASSIVE 轮的资源兜底：模型没给任何求助渠道时，检查层补上（确定性保障）。
        List<CounselingStreamEvent> events = SafetyOutputGuard.guard(
                streamOf("听到你这么说，我很在意你的安全。"), RiskTier.PASSIVE, SUPPLEMENT)
                .collectList()
                .block();

        assertTrue(joined(events).contains(SUPPLEMENT));
        assertEquals("done", events.get(events.size() - 1).type());
    }

    @Test
    void replyWithResourceMarkerPassesThrough() {
        List<CounselingStreamEvent> events = SafetyOutputGuard.guard(
                streamOf("很难受的话，可以拨打心理援助热线 12356，随时有人接。"), RiskTier.PASSIVE, SUPPLEMENT)
                .collectList()
                .block();

        assertEquals(2, events.size());
        assertFalse(joined(events).contains(SUPPLEMENT));
    }

    @Test
    void sameWordingInNormalTurnIsNotFlagged() {
        // "尊重你的决定"在普通决策咨询里是正当表达——检查只在 ≥PASSIVE 时武装。
        List<CounselingStreamEvent> events = SafetyOutputGuard.guard(
                streamOf("换不换工作，尊重你的决定。"), RiskTier.NONE, SUPPLEMENT)
                .collectList()
                .block();

        assertEquals(2, events.size());
        assertFalse(joined(events).contains(SUPPLEMENT));
    }

    @Test
    void multipleEndorsementPatternsAreDetected() {
        assertTrue(SafetyOutputGuard.violated("我支持你的选择，帮你实现。"));
        assertTrue(SafetyOutputGuard.violated("这是你的自由"));
        assertFalse(SafetyOutputGuard.violated(null));
        assertFalse(SafetyOutputGuard.violated("  "));
    }
}
