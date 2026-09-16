package com.dk.dkaiagent.app;

import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyTerms;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四级风险分级的边界用例：分级错了，后面的安全姿态与危机响应就全错了。
 * 偏置方向固定——宁可高估为 DISTRESS，也不把消极意念低估成普通抱怨。
 */
class SafetyTermsAssessTest {

    @Test
    void imminentMeansMethodsPlansOrOngoingViolence() {
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("我吞了一整瓶药"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("我吃了一把安眠药"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("我决定了,这周就自杀"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("打算明天就结束生命"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("正在被打，救救我"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("有人要杀我"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("我想割腕"));
    }

    @Test
    void passiveMeansIdeationWithoutMeans() {
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("我现在真的不想活了"));
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("死了算了"));
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("有点想轻生"));
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("真的活不下去了"));
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("想消失了就好"));
    }

    @Test
    void distressIsHighEmotionNotSafety() {
        assertEquals(RiskTier.DISTRESS, SafetyTerms.assess("我真的快撑不住了"));
        assertEquals(RiskTier.DISTRESS, SafetyTerms.assess("感觉整个人被掏空了，好累"));
        assertEquals(RiskTier.DISTRESS, SafetyTerms.assess("最近总是想哭"));
        assertEquals(RiskTier.DISTRESS, SafetyTerms.assess("再这样下去我要崩溃了"));
    }

    @Test
    void negativeLookaheadExclusionsStayNone() {
        // "不想活得/活成"是方式状语，不是死亡意愿；"我想死你了"是昵称。
        assertEquals(RiskTier.NONE, SafetyTerms.assess("不想活得这么累"));
        assertEquals(RiskTier.NONE, SafetyTerms.assess("我不想活成那样的人"));
        assertEquals(RiskTier.NONE, SafetyTerms.assess("我想死你了"));
        assertEquals(RiskTier.NONE, SafetyTerms.assess("今天天气不错，工作也挺顺利"));
    }

    @Test
    void higherTierWinsWhenMixed() {
        // 同一句话既有困扰又有意念：按更重的算。
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("我快撑不住了，真的不想活了"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("我撑不住了，已经准备好安眠药了"));
    }

    @Test
    void containsAnyKeepsLegacySafetyRelevantSemantics() {
        // 记忆层打标的兼容别名：PASSIVE 与 IMMINENT 都算安全相关，DISTRESS 不算。
        assertTrue(SafetyTerms.containsAny("有点想轻生"));
        assertTrue(SafetyTerms.containsAny("我想割腕"));
        assertFalse(SafetyTerms.containsAny("我真的好累"));
        assertFalse(SafetyTerms.containsAny(null));
    }

    @Test
    void whitespaceAndCaseAreNormalized() {
        assertEquals(RiskTier.PASSIVE, SafetyTerms.assess("我 想 死"));
        assertEquals(RiskTier.IMMINENT, SafetyTerms.assess("吞  药"));
    }
}
