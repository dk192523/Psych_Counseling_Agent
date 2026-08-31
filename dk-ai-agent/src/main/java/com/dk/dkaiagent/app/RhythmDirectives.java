package com.dk.dkaiagent.app;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 提问节奏限速器（确定性规则，零 LLM 成本）。
 *
 * <p>"问卷感"的直接来源是每一轮都以提问收尾。真人咨询师的提问是间歇的：连续追问两三轮后，
 * 会停一轮只做反映。这个判定不需要模型——数一数最近几条消息里的问号就够了，规则透明、
 * 可单测、不增加任何 LLM 前置调用（快速模式的首字延迟红线）。</p>
 *
 * <p>输入为按时间升序的最近消息片段；输出为注入 system prompt 的节奏指令，
 * 无触发时返回空串（prompt 与历史版本完全一致）。</p>
 */
public final class RhythmDirectives {

    /** 助手连续以问句收尾达到该轮数时，下一轮强制转为纯反映。 */
    private static final int QUESTION_STREAK_LIMIT = 2;
    /** 用户回复不超过该 code point 数视为回避/无力展开信号（"嗯""不知道""还行"级别）。 */
    private static final int EVASION_MAX_CODE_POINTS = 3;

    private RhythmDirectives() {
    }

    /**
     * @param recentMessages 按时间升序的最近消息（通常 6 条：3 轮问答）
     * @return 节奏指令；无触发时返回空串
     */
    public static String build(List<Message> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return "";
        }
        int questionStreak = trailingQuestionStreak(recentMessages);
        if (questionStreak >= QUESTION_STREAK_LIMIT) {
            // 连击优先于回避：连续追问本身就该停，此时追加回避判断只会稀释指令。
            return "\n\n【节奏约束（系统规则，必须遵守）】你已经连续 " + questionStreak
                    + " 轮以提问结束。这一轮不要提出任何问题，"
                    + "只用一两句话反映对方的感受或总结你听到的内容，把说话的空间还给对方。";
        }
        if (isEvasiveReplyAfterQuestion(recentMessages)) {
            return "\n\n【节奏约束（系统规则，必须遵守）】对方刚才的回答很短，可能在回避、"
                    + "犹豫或没有力气展开。这一轮不要追问同一件事，先接住TA此刻的状态，"
                    + "问题最多降到一个是非级的小步骤，并让对方知道不想说的话题随时可以跳过。";
        }
        return "";
    }

    /**
     * 连续提问轮数：从最新的 assistant 消息往回数，中间隔着的用户轮（短回复）不打断计数——
     * "连续两轮以提问结束"指的是最近两个 assistant 回合都以问句收尾，
     * 中间的用户短答正是需要停下来的原因。遇到第一条不带问句的 assistant 消息即停。
     */
    private static int trailingQuestionStreak(List<Message> messages) {
        int i = messages.size() - 1;
        while (i >= 0 && messages.get(i) instanceof UserMessage) {
            i--;
        }
        int streak = 0;
        while (i >= 0) {
            Message message = messages.get(i);
            if (message instanceof UserMessage) {
                i--;
                continue;
            }
            if (message instanceof AssistantMessage assistant
                    && containsQuestion(assistant.getText())) {
                streak++;
                i--;
                continue;
            }
            break;
        }
        return streak;
    }

    /**
     * 回避信号 = 用户最后一条极短（≤3 code point），且它前面的 assistant 消息带着问句。
     * 两个条件缺一不可：短答若是对纯反映的呼应（"嗯"），是正常跟进，不是回避。
     */
    private static boolean isEvasiveReplyAfterQuestion(List<Message> messages) {
        int lastIndex = messages.size() - 1;
        if (!(messages.get(lastIndex) instanceof UserMessage lastUser)) {
            return false;
        }
        String text = lastUser.getText();
        if (text == null || text.isBlank()
                || text.codePointCount(0, text.length()) > EVASION_MAX_CODE_POINTS) {
            return false;
        }
        return lastIndex > 0
                && messages.get(lastIndex - 1) instanceof AssistantMessage previous
                && containsQuestion(previous.getText());
    }

    private static boolean containsQuestion(String text) {
        return text != null && (text.contains("？") || text.contains("?"));
    }
}
