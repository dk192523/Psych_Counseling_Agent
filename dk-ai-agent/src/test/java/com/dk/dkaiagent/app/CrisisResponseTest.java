package com.dk.dkaiagent.app;

import com.dk.dkaiagent.memory.SafetyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrisisResponseTest {

    private final SafetyProperties safety = new SafetyProperties();
    private final CrisisResponse response = new CrisisResponse(safety);

    @Test
    void victimGetsProtectionInstructionsForEveryRenderAndSupplement() {
        // 连续调用超过旧自伤模板的轮换周期，避免只验证了碰巧不带自伤措辞的一套变体。
        for (int i = 0; i < 6; i++) {
            for (String text : List.of(response.render("他现在拿刀堵着我"),
                    response.supplement("他现在拿刀堵着我"),
                    response.supplement("他现在拿刀堵着我", true))) {
                assertTrue(text.contains("不增加危险"));
                assertTrue(text.contains("不要争辩、夺取武器"));
                assertTrue(text.contains("当地报警或紧急服务"));
                assertFalse(text.contains("伤害自己"));
                assertFalse(text.contains("这些念头"));
                assertFalse(text.contains("12356"));
            }
        }
    }

    @Test
    void intentToHarmOthersGetsDeescalationAndImmediateLocalHelp() {
        for (String text : List.of(response.render("我现在要拿刀杀了他"),
                response.supplement("我现在要拿刀杀了他"),
                response.supplement("我现在要拿刀杀了他", true))) {
            assertTrue(text.contains("暂停接近对方"));
            assertTrue(text.contains("与危险物和对方保持距离"));
            assertTrue(text.contains("寻找线下帮助"));
            assertTrue(text.contains("当地报警、急救或紧急服务"));
            assertFalse(text.contains("伤害自己"));
            assertFalse(text.contains("那些念头"));
            assertFalse(text.contains("12356"));
        }
    }

    @Test
    void legacyNoArgumentMethodsKeepConfiguredSelfHarmResources() {
        safety.setHotlines(List.of("已配置的心理援助资源"));
        assertTrue(response.render().contains("已配置的心理援助资源"));
        assertTrue(response.supplement().contains("已配置的心理援助资源"));
        assertTrue(response.supplement("我不想活了").contains("已配置的心理援助资源"));
        assertTrue(response.supplement("我不想活了", true).contains("已配置的心理援助资源"));
    }

    @Test
    void historicalRiskRetainsConfiguredResourcesWithoutInventingCurrentIntent() {
        safety.setHotlines(List.of("已配置的心理援助资源"));
        String text = response.supplement("嗯，我还在", true);
        assertTrue(text.contains("你和身边人的安全"));
        assertTrue(text.contains("当地报警、急救或紧急服务"));
        assertTrue(text.contains("已配置的心理援助资源"));
        assertFalse(text.contains("念头"));
        assertFalse(text.contains("伤害自己"));
    }

    @Test
    void neutralFollowupGetsGenericSafetyReminderWithoutInventingSelfHarmIntent() {
        String text = response.supplement("嗯，我还在");
        assertTrue(text.contains("你和身边人的安全"));
        assertTrue(text.contains("当地报警、急救或紧急服务"));
        assertFalse(text.contains("念头"));
        assertFalse(text.contains("伤害自己"));
    }
}
