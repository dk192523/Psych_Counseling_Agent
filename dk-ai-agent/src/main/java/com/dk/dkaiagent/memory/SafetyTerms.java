package com.dk.dkaiagent.memory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 危机词表与四级风险判定：记忆层（安全备注打标）、pipeline（危机拦截）、深度 Agent
 * （安全前置检查）共用的唯一事实源。
 *
 * <p>判定刻意偏向误报：多一条安全备注或一次标准模式回退很便宜，漏掉一个危机信号不可接受。
 * 裸词如"自杀"覆盖一切措辞（"这周就自杀"、"我决定了,这周就自杀"）；
 * "吞了一整瓶药"这类间隔表达由 patterns 在去空白归一后匹配。</p>
 *
 * <p>两级例外：裸子串测试会在日常抱怨与昵称上误触发——"不想活"也出现在
 * "不想活得这么累"/"不想活成那样"里，"想死"也出现在"我想死你了"里，
 * 因此表达为带负向先行断言的 patterns。真正含混的表达仍然命中
 * （"不想活在这样的日子里"、"我不想死"都保持 PASSIVE），因为猜错的方向代价更高。</p>
 *
 * <p>四级语义见 {@link RiskTier}。分级只在词面规则内做，不做任何"意图理解"——
 * 那是回答模型的职责，这里只负责把有限的确定性信号用足。</p>
 */
public final class SafetyTerms {

    /** 紧迫危险：具体手段、进行中的暴力、外部加害。命中即绕过 LLM 聊天链。 */
    private static final Set<String> IMMINENT_TERMS = Set.of(
            "割腕", "跳楼", "跳河", "上吊", "烧炭", "卧轨", "吞药", "自残",
            "正在被打", "有人要杀我"
    );

    /** 被动消极意念：有念头、无手段/计划。普通响应链继续，但强制安全姿态。 */
    private static final Set<String> PASSIVE_TERMS = Set.of(
            "自杀", "活不下去", "死了算了", "去死", "寻死", "自尽", "轻生", "结束生命"
    );

    /** 强情绪困扰：不是安全问题，只把回应姿态推向倾听与陪伴。
     *  与 ai-worker 的 `_DISTRESS_MARKERS` 保持同步（worker tests/test_service.py 有一致性
     *  测试锁定）。刻意的语义差异："活不下去"在 Java 侧归 PASSIVE（意念级，分级更重），
     *  不在本表；worker 侧 markers 只驱动 listen 姿态，不需要意念级区分——两侧都不要加它。 */
    private static final Set<String> DISTRESS_TERMS = Set.of(
            "撑不住", "崩溃", "被掏空", "熬不住", "受不了", "绝望",
            "好累", "太累", "喘不过气", "撑不下去", "想哭"
    );

    /**
     * 间隔表达的危机短语（中间字数可变："吞了一整瓶药"、"吃了一把安眠药"），
     * 以及两类裸子串会误触发的表达（见类 javadoc）。
     */
    private static final List<Pattern> IMMINENT_PATTERNS = List.of(
            Pattern.compile("吞[^，。！？\\n]{0,6}药"),
            Pattern.compile("吃[^，。！？\\n]{0,6}安眠药"),
            // 计划/决定/手段获取 + 意念动作或药物："我决定了,这周就自杀"、"已经准备好安眠药了"。
            // 间隔允许全角逗号——"决定了，今晚就自杀"是计划而非"决定了一个不相干的事"，
            // 动作词本身足够特定，跨逗号误报风险低。
            Pattern.compile("(已经|正在|打算|准备|计划|决定|买了|囤了)[^。！？\\n]{0,10}(自杀|轻生|结束生命|离开这个世界|去死|安眠药)")
    );

    private static final List<Pattern> PASSIVE_PATTERNS = List.of(
            Pattern.compile("不想活(?![得成])"),
            Pattern.compile("想死(?![你您他她它们妳])"),
            Pattern.compile("想消失(了|就好)"),
            Pattern.compile("睡过去(就|一劳永逸)?好了?")
    );

    private SafetyTerms() {
    }

    /**
     * 四级风险评估：IMMINENT &gt; PASSIVE &gt; DISTRESS &gt; NONE。
     * 归一化（去空白 + 小写）与原 containsAny 保持一致。
     */
    public static RiskTier assess(String text) {
        if (text == null) {
            return RiskTier.NONE;
        }
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (matches(normalized, IMMINENT_TERMS, IMMINENT_PATTERNS)) {
            return RiskTier.IMMINENT;
        }
        if (matches(normalized, PASSIVE_TERMS, PASSIVE_PATTERNS)) {
            return RiskTier.PASSIVE;
        }
        if (DISTRESS_TERMS.stream().anyMatch(normalized::contains)) {
            return RiskTier.DISTRESS;
        }
        return RiskTier.NONE;
    }

    /**
     * 兼容别名：安全相关（PASSIVE 或 IMMINENT）。记忆层的安全备注打标继续用它——
     * 语义与改造前完全一致（当时的"命中"就是这两级之和）。
     */
    public static boolean containsAny(String text) {
        return assess(text).isSafetyRelevant();
    }

    /** 供审计/一致性测试导出：与 ai-worker `_DISTRESS_MARKERS` 的同步契约见本类字段注释。 */
    public static java.util.Set<String> getDistressTerms() {
        return Set.copyOf(DISTRESS_TERMS);
    }

    private static boolean matches(String normalized, Set<String> terms, List<Pattern> patterns) {
        if (terms.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return patterns.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
