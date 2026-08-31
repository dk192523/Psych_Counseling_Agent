package com.dk.dkaiagent.app;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RhythmDirectivesTest {

    private static Message user(String text) {
        return new UserMessage(text);
    }

    private static Message ai(String text) {
        return new AssistantMessage(text);
    }

    @Test
    void emptyHistoryProducesNoDirective() {
        assertEquals("", RhythmDirectives.build(List.of()));
        assertFalse(RhythmDirectives.build(null).contains("节奏约束"));
    }

    @Test
    void singleQuestionTurnIsStillAllowed() {
        // 只问了一轮就停，是正常节奏，不触发限速。
        String directive = RhythmDirectives.build(List.of(
                user("最近很累"),
                ai("听起来真的很撑不住了。这种状态是从什么时候开始的？"),
                user("大概一个月了")
        ));
        assertEquals("", directive);
    }

    @Test
    void twoConsecutiveQuestionTurnsForceAListenTurn() {
        String directive = RhythmDirectives.build(List.of(
                user("最近很累"),
                ai("这种状态是从什么时候开始的？"),
                user("一个月左右"),
                ai("是一个人扛着，还是身边有人知道？"),
                user("就自己扛着")
        ));
        assertTrue(directive.contains("连续 2 轮"));
        assertTrue(directive.contains("不要提出任何问题"));
        assertTrue(directive.contains("反映"));
    }

    @Test
    void questionStreakStopsAtNonQuestionTurn() {
        // 中间隔了一轮纯反映：连击被打断，不触发限速。
        String directive = RhythmDirectives.build(List.of(
                ai("什么时候开始的？"),
                user("一个月前"),
                ai("嗯，一个月，真的不容易。"),
                user("是的"),
                ai("身边有人知道吗？")
        ));
        assertEquals("", directive);
    }

    @Test
    void shortUserReplyAfterQuestionTriggersBackOff() {
        String directive = RhythmDirectives.build(List.of(
                user("我最近压力很大"),
                ai("压力大是来自工作还是家庭？"),
                user("嗯")
        ));
        assertTrue(directive.contains("不要追问同一件事"));
        assertTrue(directive.contains("不想说"));
        assertFalse(directive.contains("连续"));
    }

    @Test
    void shortReplyWithoutPrecedingQuestionIsNeutral() {
        // "嗯"跟在纯反映后面：没有可回避的问题，不需要介入。
        String directive = RhythmDirectives.build(List.of(
                user("最近好累"),
                ai("嗯，这种累撑了很久了吧。"),
                user("嗯")
        ));
        assertEquals("", directive);
    }
}
