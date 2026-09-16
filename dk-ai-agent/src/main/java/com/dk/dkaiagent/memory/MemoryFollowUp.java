package com.dk.dkaiagent.memory;

import java.util.Optional;

/**
 * 会话回访驱动（对话内核 v2 第二阶段）："上次你提到…后来怎么样了" 这种原话级的被记住感，
 * 是摘要回访做不到、但对咨询关系最重要的细度。实现刻意走零延迟路线：
 * 不新增任何模型/worker 调用，只解析长期摘要中本就存在的「待确认问题」段落。
 *
 * <p>触发条件：距上一轮对话 ≥{@value #REVISIT_GAP_HOURS} 小时（用户隔了一段时间回来）
 * 且摘要里有具体的待确认事项。同一会话内的连续轮次不会触发——咨询师也不是每句话都翻笔记。</p>
 */
public final class MemoryFollowUp {

    /** 距上一轮超过该小时数视为"隔了一段时间回来"，回访才自然。 */
    public static final double REVISIT_GAP_HOURS = 6.0;

    private static final String PENDING_SECTION_HEADING = "## 待确认问题";

    private MemoryFollowUp() {
    }

    public static boolean isRevisitOpening(double hoursSincePreviousTurn) {
        return hoursSincePreviousTurn >= REVISIT_GAP_HOURS;
    }

    /**
     * @param digest                  长期摘要原文（可含框架语，解析按 "## 待确认问题" 标题定位）
     * @param hoursSincePreviousTurn  距上一轮的小时数
     * @return 回访指令；无触发时返回空串
     */
    public static String build(String digest, double hoursSincePreviousTurn) {
        if (digest == null || digest.isBlank() || !isRevisitOpening(hoursSincePreviousTurn)) {
            return "";
        }
        return firstPendingQuestion(digest)
                .map(question -> "\n\n【会话回访（系统规则）】这是隔了一段时间后的新一轮对话。长期摘要中"
                        + "有一个尚未确认的事项：「" + question + "」。如果它与用户此刻的发言相关，"
                        + "可以自然地回访一次（如“上次你提到…，后来怎么样了？”）；只回访这一个，语气要轻，"
                        + "对方不接就立刻放下，绝不追问；与当前发言无关就不要提。")
                .orElse("");
    }

    /**
     * 提取「## 待确认问题」段落的第一条非空待确认项（去掉列表符号）；
     * 段落缺失、内容为"暂无/无"时返回 empty。
     */
    public static Optional<String> firstPendingQuestion(String digest) {
        if (digest == null || digest.isBlank()) {
            return Optional.empty();
        }
        String[] lines = digest.split("\n");
        boolean inSection = false;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                if (inSection) {
                    break; // 进入下一段落：本段没有可用的待确认项
                }
                inSection = line.startsWith(PENDING_SECTION_HEADING);
                continue;
            }
            if (!inSection || line.isEmpty()) {
                continue;
            }
            if (line.startsWith("暂无") || line.equals("无")) {
                return Optional.empty();
            }
            String item = line.replaceFirst("^[-*•]\\s*", "").trim();
            if (!item.isEmpty()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}
