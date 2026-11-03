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

    /** 只用于选择安全文案；风险等级仍由 {@link #assess(String)} 决定。 */
    public enum CrisisCategory {
        SELF_HARM, HARM_TO_OTHERS, DANGER_FROM_OTHERS, UNSPECIFIED
    }

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

    /**
     * 人际危险只匹配明确的主语、计划/当前时态和伤害动作，不把“刀”“杀人”等裸词当危机。
     * 这些规则与原自伤规则分开处理，避免改变原有偏保守的自伤分级契约。
     */
    private static final List<Pattern> HARM_TO_OTHERS_PATTERNS = List.of(
            Pattern.compile("(?:^|[，,：:])我(?:现在|马上|立刻|今晚|今天|明天)?"
                    + "(?:就要|要|准备|打算|计划|决定)(?:马上|立刻|现在|今晚|今天|明天)?"
                    + "(?:(?:拿|用|带着|带上)(?:刀|匕首|菜刀|枪))?(?:就|去|过去|冲过去)?"
                    + "(?:杀(?:了|死)?|砍(?:死|伤)?|刺(?:死|伤)?|捅(?:死|伤)?|伤害)"
                    + "(?:他|她|他们|她们|人|同学|室友|家人|某人)")
    );

    private static final List<Pattern> DANGER_FROM_OTHERS_PATTERNS = List.of(
            Pattern.compile("(?:^|[，,：:])(?:他|她|他们|她们|有人|对方|室友|同学|男友|女友|丈夫|妻子|前任)"
                    + "(?:现在|正在|这会儿)(?:正)?(?:拿着?|持|举着?|用)(?:刀|匕首|菜刀|枪)"
                    + "(?:堵(?:着|住)|围(?:着|住)|追(?:着|赶|杀)?|砍|刺|捅|杀|威胁着?|指着?)我"),
            Pattern.compile("(?:^|[，,：:])(?:他|她|他们|她们|有人|对方|室友|同学|男友|女友|丈夫|妻子|前任)"
                    + "(?:现在|正在|这会儿)(?:掐(?:着|住)我的脖子|勒(?:着|住)我的脖子|殴打我|打我)")
    );

    private static final Pattern QUOTED_TEXT = Pattern.compile(
            "“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』|\"[^\"]*\"");

    /** 有限的转述/假设词面排除，只用于新增的人际危险规则，不推断说话者意图。 */
    private static final Pattern INDIRECT_DANGER_CONTEXT = Pattern.compile(
            "(?:电影|电视剧|小说|剧本|故事|新闻|报道|台词|游戏)(?:里|中|上|说|写|讲|描述|报道)"
                    + "|(?:他|她|朋友|同学|老师|角色|主人公)(?:刚才|曾经|正在)?(?:说|写道|提到|表示)"
                    + "|听说|据说|转述|复述|举例|例如|比如|假如|如果|假设");

    private static final Pattern DENIED_DANGER_SUFFIX = Pattern.compile(
            "^(?:这句话|的说法)?(?:不是真的|是假的|只是(?:台词|例子|假设)|是(?:台词|转述|电影台词))");

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
        if (matches(normalized, IMMINENT_TERMS, IMMINENT_PATTERNS)
                || matchesInterpersonalDanger(normalized, HARM_TO_OTHERS_PATTERNS)
                || matchesInterpersonalDanger(normalized, DANGER_FROM_OTHERS_PATTERNS)) {
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
     * 使用与分级相同的人际危险规则及转述排除，避免分级和文案分类各维护一套关键词。
     * 当前外部危险优先；无安全信号时返回 UNSPECIFIED，供历史风险轮次使用通用提醒。
     */
    public static CrisisCategory crisisCategory(String text) {
        if (text == null) {
            return CrisisCategory.UNSPECIFIED;
        }
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("正在被打") || normalized.contains("有人要杀我")
                || matchesInterpersonalDanger(normalized, DANGER_FROM_OTHERS_PATTERNS)) {
            return CrisisCategory.DANGER_FROM_OTHERS;
        }
        if (matchesInterpersonalDanger(normalized, HARM_TO_OTHERS_PATTERNS)) {
            return CrisisCategory.HARM_TO_OTHERS;
        }
        return assess(text).isSafetyRelevant() ? CrisisCategory.SELF_HARM : CrisisCategory.UNSPECIFIED;
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

    private static boolean matchesInterpersonalDanger(String normalized, List<Pattern> patterns) {
        // 引语换成句界，防止删除引语后把两侧文本拼成新的危险断言。
        String directText = QUOTED_TEXT.matcher(normalized).replaceAll("。");
        for (String sentence : directText.split("[。！？!?；;]+")) {
            for (Pattern pattern : patterns) {
                var matcher = pattern.matcher(sentence);
                while (matcher.find()) {
                    if (!INDIRECT_DANGER_CONTEXT.matcher(sentence.substring(0, matcher.start())).find()
                            && !DENIED_DANGER_SUFFIX.matcher(sentence.substring(matcher.end())).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matches(String normalized, Set<String> terms, List<Pattern> patterns) {
        if (terms.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return patterns.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
