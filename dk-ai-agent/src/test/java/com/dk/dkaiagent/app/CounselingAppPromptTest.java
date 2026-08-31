package com.dk.dkaiagent.app;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System prompt 行为契约的回归锚点：聊天逻辑改造把"每轮必提问"改成了
 * "默认反映、按状态选动作"。这些断言防止后续改动悄悄把问卷式节奏带回来。
 */
class CounselingAppPromptTest {

    private static String systemPrompt() throws Exception {
        Field field = CounselingApp.class.getDeclaredField("SYSTEM_PROMPT");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    void promptEncodesQuestionerCompanionRoleReversal() throws Exception {
        assertTrue(systemPrompt().contains("你是提问者与陪伴者，用户是讲述者"));
    }

    @Test
    void promptMakesReflectionTheDefaultAction() throws Exception {
        assertTrue(systemPrompt().contains("提问是工具，不是每一轮的默认结尾"));
        assertTrue(systemPrompt().contains("默认选反映"));
    }

    @Test
    void promptCarriesEvasionBackOffProtocol() throws Exception {
        assertTrue(systemPrompt().contains("不追问同一件事"));
        assertTrue(systemPrompt().contains("随时可以跳过"));
    }

    @Test
    void promptObeysInjectedRhythmConstraints() throws Exception {
        assertTrue(systemPrompt().contains("【节奏约束】"));
    }

    @Test
    void promptKeepsVentingBudgetAndQuestionFormRules() throws Exception {
        assertTrue(systemPrompt().contains("20 到 80 字且零提问"));
        assertTrue(systemPrompt().contains("慎用“为什么”"));
    }

    @Test
    void promptKeepsThreeStageConsentGate() throws Exception {
        // 节奏改造不得侵蚀许可门槛：完整梳理必须经用户同意。
        assertTrue(systemPrompt().contains("必须明确征得同意"));
        assertTrue(systemPrompt().contains("【阶段三：经同意后梳理】"));
    }
}
