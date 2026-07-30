package com.dk.dkaiagent.memory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Crisis-term vocabulary: the single source of truth shared by the memory layer (safety flagging
 * for verbatim safety notes) and the deep agent's immediate-safety fast path.
 *
 * <p>The gate intentionally leans toward false positives: an extra safety note or one standard-mode
 * fallback is cheap, a missed crisis signal is not. Bare terms like "自杀" cover every phrasing
 * ("这周就自杀", "我决定了,这周就自杀" ...); gapped expressions like "吞了一整瓶药" are matched by
 * patterns after whitespace normalization.</p>
 */
public final class SafetyTerms {

    private static final Set<String> CRISIS_TERMS = Set.of(
            "自杀", "不想活", "活不下去", "想死", "死了算了", "去死", "寻死", "自尽", "轻生",
            "结束生命", "割腕", "跳楼", "跳河", "上吊", "烧炭", "卧轨", "吞药", "自残",
            "正在被打", "有人要杀我"
    );

    /** Gapped crisis expressions whose middle varies ("吞了一整瓶药", "吃了一把安眠药"). */
    private static final List<Pattern> CRISIS_PATTERNS = List.of(
            Pattern.compile("吞[^，。！？\\n]{0,6}药"),
            Pattern.compile("吃[^，。！？\\n]{0,6}安眠药")
    );

    private SafetyTerms() {
    }

    public static boolean containsAny(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (CRISIS_TERMS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return CRISIS_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
