package com.dk.dkaiagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;

import static com.dk.dkaiagent.rag.KnowledgeIntentRouter.Intent.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIntentRouterTest {

    @ParameterizedTest
    @ValueSource(strings = {"你好", "我今天只想倾诉，先别建议", "听我说，不用分析", "",
            "先不要解释原因", "我最近很难过，先让我说完", "让我先说完，先别解释",
            "我的性格让我很烦，先不用解释原因", "我得了抑郁症，只想倾诉",
            "咨询师提过 CBT，我今天不要分析，也不需要建议", "备考让我很焦虑，只想说说"})
    void listeningDoesNotRetrieve(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(LISTEN, route.intent());
        assertFalse(route.shouldRetrieve());
        assertTrue(route.filterExpression().contains("__no_retrieval__"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"我得了抑郁症，只想倾诉", "CBT 是什么？但先听我说",
            "咨询师保密的法律边界是什么？我也想倾诉", "只想倾诉，不过请给我一道考试题"})
    void listeningPriorityPreservesRequiredSourceGate(String query) {
        var required = KnowledgeIntentRouter.requiredDomains(query);
        if (query.equals("我得了抑郁症，只想倾诉")) {
            assertTrue(required.isEmpty());
        } else if (query.contains("考试题")) {
            assertEquals(java.util.Set.of("psych-exams"), required);
        } else if (query.contains("法律")) {
            assertEquals(java.util.Set.of("psych-policy"), required);
        } else {
            assertEquals(java.util.Set.of("psych-knowledge"), required);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CBT 是什么", "如何理解认知歪曲", "抑郁症怎么诊断",
            "咨询师保密的法律边界是什么", "危机干预要注意什么", "只想倾诉但我想自杀",
            "CBT 是什么？但先听我说", "咨询师保密的法律边界是什么？我也想倾诉"})
    void professionalQuestionsNeverUseCasesAsAuthority(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(PROFESSIONAL, route.intent());
        assertEquals(List.of("psych-knowledge", "psych-policy"), route.allowedDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"人性是复杂且不可什么的，这句话到底怎么说？",
            "心理学上人格与性格有什么区别？", "人格与性格有什么区别？",
            "什么是认知失调？", "防御机制是什么意思？", "请解释习得性无助",
            "马斯洛的需求层次理论是什么？", "关于人性的那句话出处在哪里？",
            "先让我说完，不过请解释一下投射是什么意思",
            "先不要解释原因，但我想了解人格与性格有什么不同"})
    void explicitPsychologyKnowledgeRequestsUseProfessionalSources(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(PROFESSIONAL, route.intent());
        assertEquals(List.of("psych-knowledge", "psych-policy"), route.allowedDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"先让我说完，我想伤害自己", "先不要解释原因，我不想活了",
            "备考压力太大，我想伤害自己", "考试题先放一边，我现在有自伤危机",
            "我吞了一整瓶药，只想倾诉，不要给建议"})
    void crisisTakesPrecedenceOverListeningAndExams(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(PROFESSIONAL, route.intent());
        assertEquals(List.of("psych-knowledge", "psych-policy"), route.allowedDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"我跟室友吵架了，不知道怎么面对他", "最近考试压力很大，睡不着",
            "写代码总失败，我很焦虑", "有与我相似的案例吗", "我的性格很内向，该怎么办？",
            "我在人际关系里很依恋对方，最近特别难受", "我觉得人性很复杂，和朋友吵架了",
            "我总是焦虑，到底为什么会这样？"})
    void ordinaryConcreteDistressKeepsCaseRetrieval(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(CASES, route.intent());
        assertEquals(List.of("psych-cases"), route.allowedDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"请解释 CBT，再给一个类似案例", "什么是认知失调？请给一个案例"})
    void mixedRequestExplicitlyIncludesBothGroups(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(MIXED, route.intent());
        assertEquals(List.of("psych-knowledge", "psych-policy", "psych-cases"), route.allowedDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"心理咨询师考试题，CBT 的选择题标准答案是什么",
            "人格与性格有什么区别？这道考试题的标准答案是什么", "只想倾诉，不过请给我一道考试题"})
    void examRouteDoesNotFallBackToProductionCases(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(EXAM, route.intent());
        assertEquals(List.of("psych-exams"), route.allowedDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"今天的天气怎样", "帮我写 Java 排序算法", "查一下股票价格"})
    void irrelevantRequestDoesNotRetrieve(String query) {
        var route = KnowledgeIntentRouter.route(query);
        assertEquals(IRRELEVANT, route.intent());
        assertFalse(route.shouldRetrieve());
    }

    @Test
    void nullInputIsListeningAndRouteDomainsAreImmutable() {
        assertEquals(LISTEN, KnowledgeIntentRouter.route(null).intent());
        assertThrows(UnsupportedOperationException.class,
                () -> KnowledgeIntentRouter.route("CBT").allowedDomains().add("psych-cases"));
    }

    @Test
    void filterUsesGroupedOrAndParsesWithSpringAi() {
        String filter = KnowledgeIntentRouter.filterExpression(
                List.of("psych-policy", "psych-knowledge", "psych-policy"));
        assertEquals("knowledgeBase == 'psych-counseling' && (knowledgeDomain == 'psych-knowledge'"
                + " || knowledgeDomain == 'psych-policy')", filter);
        assertNotNull(SearchRequest.builder().filterExpression(filter).build().getFilterExpression());
        assertNotNull(SearchRequest.builder().filterExpression(
                KnowledgeIntentRouter.filterExpression(List.of())).build().getFilterExpression());
    }

    @Test
    void filterRejectsUnknownDomainsAndInjectionRatherThanEscapingThem() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeIntentRouter.filterExpression(List.of("psych-prompts")));
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeIntentRouter.filterExpression(List.of("psych-cases' || title == 'x")));
    }
}
