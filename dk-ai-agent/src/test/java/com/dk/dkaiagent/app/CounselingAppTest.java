package com.dk.dkaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_INTEGRATION_TESTS", matches = "true")
class CounselingAppTest {

    /** 实时集成测试使用的固定会话归属 id（超管由 AdminBootstrap 启动时创建）。 */
    private static final long OWNER_ID = 1L;

    @Resource
    private CounselingApp counselingApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我叫小林";
        String answer = counselingApp.doChat(OWNER_ID, message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "最近学习和找实习的压力叠在一起，我总觉得自己做得不够好。";
        answer = counselingApp.doChat(OWNER_ID, message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我刚才说自己叫什么？";
        answer = counselingApp.doChat(OWNER_ID, message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithSummary() {
        String chatId = UUID.randomUUID().toString();
        String message = "你好，我是DK，最近工作压力很大，总觉得自己做得不够好。";
        CounselingApp.CounselingSummary summary = counselingApp.doChatWithSummary(message, chatId);
        Assertions.assertNotNull(summary);
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "我已经结婚了，但是婚后关系不太亲密，怎么办？";
        String answer = counselingApp.doChatWithRag(OWNER_ID, message, chatId);
        Assertions.assertNotNull(answer);
    }

}
