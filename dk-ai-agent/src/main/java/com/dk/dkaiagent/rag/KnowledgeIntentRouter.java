package com.dk.dkaiagent.rag;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Java owns the retrieval boundary; rewritten queries and worker plans cannot widen it. */
public final class KnowledgeIntentRouter {

    public enum Intent { LISTEN, PROFESSIONAL, CASES, MIXED, EXAM, IRRELEVANT }

    private static final List<String> PROFESSIONAL_DOMAINS = List.of("psych-knowledge", "psych-policy");
    private static final Set<String> RETRIEVAL_DOMAINS = Set.of(
            "psych-cases", "psych-knowledge", "psych-policy", "psych-exams");

    public record Route(Intent intent, List<String> allowedDomains) {
        public Route {
            Objects.requireNonNull(intent, "intent");
            allowedDomains = List.copyOf(allowedDomains);
            if (!RETRIEVAL_DOMAINS.containsAll(allowedDomains)) {
                throw new IllegalArgumentException("Unsupported knowledge domain");
            }
        }

        public boolean shouldRetrieve() { return !allowedDomains.isEmpty(); }

        public String filterExpression() {
            return KnowledgeIntentRouter.filterExpression(allowedDomains);
        }
    }

    private KnowledgeIntentRouter() {}

    /** Only explicit factual requests require an approved source; feelings and safety support stay available. */
    public static Set<String> requiredDomains(String query) {
        String text = Objects.toString(query, "").toLowerCase(Locale.ROOT).strip();
        if (com.dk.dkaiagent.memory.SafetyTerms.assess(text).isSafetyRelevant()) return Set.of();
        Route route = route(query);
        if (route.intent() == Intent.EXAM) return Set.of("psych-exams");
        if (route.intent() != Intent.PROFESSIONAL && route.intent() != Intent.MIXED) return Set.of();
        boolean explicitQuestion = isPsychologyKnowledgeQuestion(text) || containsAny(text,
                "是什么", "什么是", "什么意思", "怎么", "如何", "为什么", "哪些", "什么区别", "什么边界",
                "解释", "介绍", "科普", "问答", "请问", "法律边界", "专业角度", "什么", "?", "？");
        if (!explicitQuestion) return Set.of();
        boolean policy = containsAny(text, "法律", "法规", "违法", "保密", "知情同意", "伦理", "政策",
                "未成年人保护", "精神卫生法");
        boolean knowledge = !policy || containsAny(text, "cbt", "认知行为", "心理学", "心理理论", "诊断", "dsm", "icd");
        if (policy && knowledge) return Set.of("psych-policy", "psych-knowledge");
        return Set.of(policy ? "psych-policy" : "psych-knowledge");
    }

    public static Route route(String query) {
        String text = Objects.toString(query, "").toLowerCase(Locale.ROOT).strip();
        if (text.isBlank()) return routeFor(Intent.LISTEN);

        // Safety always wins; a diagnosis/topic mention alone must not override explicit listening.
        boolean crisis = com.dk.dkaiagent.memory.SafetyTerms.assess(text).isSafetyRelevant()
                || containsAny(text, "危机", "自杀", "自伤", "轻生", "不想活", "伤害自己",
                "想死", "结束生命");
        boolean professional = containsAny(text, "专业", "诊断", "抑郁症", "焦虑症", "双相", "精神分裂",
                "cbt", "认知行为", "认知歪曲", "认知扭曲", "心理学", "心理治疗", "循证", "指南",
                "量表", "dsm", "icd", "咨询伦理", "政策", "伦理", "咨询边界", "专业知识", "保密", "知情同意", "法律", "法规", "违法",
                "未成年人保护", "精神卫生法", "药物", "抗抑郁", "依恋理论")
                || crisis || isPsychologyKnowledgeQuestion(text);
        boolean cases = containsAny(text, "案例", "个案", "逐字稿", "相似经历", "类似经历", "类似情况的人");
        boolean exam = containsAny(text, "真题", "题库", "刷题", "模拟题", "选择题", "判断题", "试题",
                "考证", "备考", "考试题", "考点", "标准答案");
        boolean listening = containsAny(text, "只想倾诉", "只想说说", "只想吐槽", "听我说", "听我讲", "陪我聊",
                "不用分析", "不要分析", "不需要建议", "别给建议", "不要给建议", "先别建议",
                "不用建议", "不要建议", "先不要解释", "先别解释", "先不用解释", "先让我说完", "让我先说完");
        boolean explicitProfessionalQuestion = isPsychologyKnowledgeQuestion(text)
                || (professional && containsAny(text, "是什么", "什么是", "什么意思", "何谓", "定义",
                "怎么", "如何", "为什么", "哪些", "什么区别", "什么边界", "有什么不同",
                "请解释", "解释一下", "解释下", "介绍一下", "请介绍", "科普", "想了解"));
        boolean explicitExamRequest = containsAny(text, "真题", "题库", "刷题", "模拟题", "选择题",
                "判断题", "试题", "考试题", "考点", "标准答案");
        if (crisis) return routeFor(cases ? Intent.MIXED : Intent.PROFESSIONAL);
        if (listening && !explicitProfessionalQuestion && !explicitExamRequest) {
            return routeFor(Intent.LISTEN);
        }
        if (exam) return routeFor(Intent.EXAM);
        if (professional) return routeFor(cases ? Intent.MIXED : Intent.PROFESSIONAL);
        if (cases) return routeFor(Intent.CASES);
        if (text.matches("[\\s你好您好嗨嗯哦啊好谢谢再见晚安早安在吗!?！？。，,.～~]+")
                || List.of("hi", "hello", "thanks", "ok").contains(text)) {
            return routeFor(Intent.LISTEN);
        }
        // Do not classify ordinary exam/work stress as trivia merely because of its topic.
        boolean distress = containsAny(text, "焦虑", "难过", "难受", "压力", "担心", "害怕", "失眠",
                "崩溃", "烦", "痛苦", "孤独", "关系", "吵架", "分手", "情绪", "心情", "纠结", "抑郁");
        if (!distress && containsAny(text, "天气", "股票", "股价", "汇率", "菜谱", "编程", "写代码",
                "java", "python", "排序算法", "足球比分", "翻译这", "首都是")) {
            return routeFor(Intent.IRRELEVANT);
        }
        // Preserve the existing case retrieval path for concrete counseling narratives.
        return routeFor(Intent.CASES);
    }

    private static boolean isPsychologyKnowledgeQuestion(String text) {
        // A topic alone can be a personal narrative; require an explicit knowledge request.
        boolean psychologyTopic = containsAny(text, "心理概念", "心理术语", "心理理论", "人格", "性格",
                "人性", "依恋", "认知失调", "防御机制", "投射", "移情", "反移情", "自我效能",
                "习得性无助", "条件反射", "强化", "马斯洛", "弗洛伊德");
        boolean explanation = containsAny(text, "是什么", "什么是", "什么意思", "何谓", "定义",
                "如何理解", "怎么理解", "解释一下", "解释下", "请解释", "介绍一下", "科普",
                "有什么区别", "有何区别", "有什么不同", "有何不同", "区别是什么");
        // Route a quotation lookup to knowledge sources without supplying or inventing its wording.
        boolean sayingLookup = text.contains("人性")
                && containsAny(text, "这句话", "那句话", "说法", "名言", "原话")
                && containsAny(text, "怎么说", "怎么讲", "原文", "完整", "出处", "谁说", "什么意思");
        return (psychologyTopic && explanation) || sayingLookup;
    }

    private static Route routeFor(Intent intent) {
        return new Route(intent, switch (intent) {
            case LISTEN, IRRELEVANT -> List.of();
            case PROFESSIONAL -> PROFESSIONAL_DOMAINS;
            case CASES -> List.of("psych-cases");
            case MIXED -> List.of("psych-knowledge", "psych-policy", "psych-cases");
            case EXAM -> List.of("psych-exams");
        });
    }

    /** Only constant, allowlisted values reach the expression parser; never interpolate user text. */
    public static String filterExpression(Collection<String> allowedDomains) {
        Objects.requireNonNull(allowedDomains, "allowedDomains");
        if (allowedDomains.stream().anyMatch(domain -> domain == null || !RETRIEVAL_DOMAINS.contains(domain))) {
            throw new IllegalArgumentException("Unsupported knowledge domain");
        }
        String domains = allowedDomains.stream().distinct().sorted()
                .map(domain -> "knowledgeDomain == '" + domain + "'")
                .collect(Collectors.joining(" || "));
        // A reserved, non-ingested domain prevents unrestricted retrieval for no-retrieval routes.
        if (domains.isEmpty()) domains = "knowledgeDomain == '__no_retrieval__'";
        return "knowledgeBase == '" + PgVectorVectorStoreConfig.KNOWLEDGE_BASE_NAME + "' && (" + domains + ")";
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
