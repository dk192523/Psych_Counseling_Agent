package com.dk.dkaiagent.app;

import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyTerms;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyDirectivesTest {

    @Test
    void outputGuardUsesUserHistoryButNotAssistantQuotes() {
        assertEquals(RiskTier.PASSIVE, SafetyDirectives.outputRiskTier(List.of(user("我不想活了"), user("嗯"))));
        assertEquals(RiskTier.NONE, SafetyDirectives.outputRiskTier(List.of(new AssistantMessage("我不想活了"), user("嗯"))));
        assertEquals(RiskTier.NONE, SafetyDirectives.outputRiskTier(List.of(user("今天很好"), user("嗯"))));
    }

    private Message user(String text) {
        return new UserMessage(text);
    }

    @Test
    void passiveIdeationInjectsSafetyPosture() {
        String directive = SafetyDirectives.build(List.of(
                user("最近压力很大"),
                new AssistantMessage("发生了什么？"),
                user("我不想活了")));

        assertTrue(directive.contains("【安全姿态"));
        assertTrue(directive.contains("安全确认与陪伴"));
        assertTrue(directive.contains("优先于一切节奏约束"));
    }

    @Test
    void imminentInjectsDefensiveDirective() {
        String directive = SafetyDirectives.build(List.of(user("我吞了一整瓶药")));
        // pipeline 应已拦截 IMMINENT，这里的指令是进程内直调的防御兜底。
        assertTrue(directive.contains("紧迫的安全风险"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("我吞了一整瓶药"));
    }

    @Test
    void distressReinforcesListening() {
        String directive = SafetyDirectives.build(List.of(user("我真的快撑不住了")));
        assertTrue(directive.contains("不要提问"));
    }

    @Test
    void neutralUserMessageProduceNothing() {
        assertEquals("", SafetyDirectives.build(List.of(user("今天加班有点烦"))));
        assertEquals("", SafetyDirectives.build(List.of()));
    }

    @Test
    void directiveKeysToLastUserMessageEvenWithAssistantTail() {
        // 语义契约：当前输入 = 最近一条 user 消息（history 尾部的 assistant 属于上一轮回答，
        // 判定不受它干扰）。
        String directive = SafetyDirectives.build(List.of(user("好累"), new AssistantMessage("嗯。")));
        assertTrue(directive.contains("情绪强度很高"));
    }

    @Test
    void sustainedAttentionAfterPassiveHistory() {
        // B1 持续关注：历史有消极意念、本轮已缓和——注入持续关注而非当作过去。
        String directive = SafetyDirectives.build(List.of(
                user("我不想活了"),
                new AssistantMessage("我很在意你现在的安全，你现在还好吗？"),
                user("还好啦，就是有点累")));

        assertTrue(directive.contains("【持续关注"));
        assertTrue(directive.contains("不要当作话题已经过去"));
        // 当前已缓和：不再注入完整的 PASSIVE 安全姿态
        assertFalse(directive.contains("安全确认与陪伴"));
    }

    @Test
    void currentTierStillWinsWhenItIsHeavier() {
        // 历史有 DISTRESS、当前是 PASSIVE：以更重的当前分级为主。
        String directive = SafetyDirectives.build(List.of(
                user("我快撑不住了"),
                new AssistantMessage("我在。"),
                user("我不想活了")));

        assertTrue(directive.contains("安全确认与陪伴"));
        // 历史分级不高于当前时不追加持续关注（PASSIVE 姿态本身已覆盖）
        assertFalse(directive.contains("【持续关注"));
    }
}
