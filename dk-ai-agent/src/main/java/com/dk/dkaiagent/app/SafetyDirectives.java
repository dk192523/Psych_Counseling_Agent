package com.dk.dkaiagent.app;

import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyTerms;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 安全姿态指令：根据用户消息的风险分级，向 system prompt 注入与风险匹配的
 * 回应姿态要求。与 {@link RhythmDirectives} 同源同结构——都从最近消息片段做确定性判定，
 * 都经 systemPromptWithDigest 注入三条链路。
 *
 * <p>分级扫描（B1 持续关注）：不只看当前输入——扫描窗口内**所有** user 消息取最高分级。
 * 历史里出现过消极意念而本轮发言已缓和时，注入"持续关注"指令：咨询师不会因为一句
 * "还好啦"就当什么都没发生过。</p>
 *
 * <p>与节奏约束的关系：安全姿态**优先于**节奏约束（注入顺序在后，且指令内显式声明）。
 * IMMINENT 理论上不会走到这里（pipeline 已拦截为模板响应），保留一条防御性指令兜底。</p>
 */
public final class SafetyDirectives {

    /** Shared persisted-history window for prompt posture and output checking (about three turns).
     * Risk leaves this window as new turns arrive; this is not a clinical declaration of safety.
     */
    public static final int CONTEXT_MESSAGES = 6;

    public static RiskTier outputRiskTier(List<Message> recentMessages) {
        if (recentMessages == null) return RiskTier.NONE;
        return recentMessages.stream().filter(UserMessage.class::isInstance)
                .map(message -> SafetyTerms.assess(message.getText()))
                .max(java.util.Comparator.comparingInt(Enum::ordinal)).orElse(RiskTier.NONE);
    }

    private static final org.slf4j.Logger auditLog =
            org.slf4j.LoggerFactory.getLogger(SafetyDirectives.class);

    private SafetyDirectives() {
    }

    /**
     * @param recentMessages 按时间升序的最近消息（含本轮已落库的当前输入）
     * @return 安全姿态指令；无触发时返回空串
     */
    public static String build(List<Message> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return "";
        }
        String currentMessage = null;
        RiskTier historicTier = RiskTier.NONE;
        // 从后往前找当前输入（最后一条 user），更早的 user 消息参与历史最高分级。
        boolean foundCurrent = false;
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            Message message = recentMessages.get(i);
            if (!(message instanceof UserMessage user)) {
                continue;
            }
            if (!foundCurrent) {
                currentMessage = user.getText();
                foundCurrent = true;
                continue;
            }
            RiskTier tier = SafetyTerms.assess(user.getText());
            if (tier.ordinal() > historicTier.ordinal()) {
                historicTier = tier;
            }
        }
        if (currentMessage == null) {
            return "";
        }
        RiskTier currentTier = SafetyTerms.assess(currentMessage);
        if (currentTier.isSafetyRelevant()) {
            // A2 审计：与 pipeline 的 IMMINENT WARN 分工——这里覆盖的是"未走模板通道"
            // 的 PASSIVE 轮次（以及防御性兜底的 IMMINENT）。正文绝不入日志。
            auditLog.info("安全事件 tier={}（安全姿态注入）", currentTier);
        }

        StringBuilder directives = new StringBuilder();
        switch (currentTier) {
            case IMMINENT -> directives.append("\n\n【安全姿态（系统规则，优先于一切节奏约束）】用户表达了紧迫的安全风险。")
                    .append("立即确认现实安全（是否正在发生、有无计划或工具、身边有无可信任的人），")
                    .append("优先提供求助资源（急救、心理援助热线），暂停一切其他流程。");
            case PASSIVE -> directives.append("\n\n【安全姿态（系统规则，优先于一切节奏约束）】用户表达了消极意念。")
                    .append("本轮的首要任务是安全确认与陪伴，其余一切流程让位：让TA感到被听见；")
                    .append("温和但直接地询问现在的安全状况与身边可信任的支持；")
                    .append("提及可以求助的资源（心理援助热线、信任的人）；不评判、不恐慌、不说教。")
                    .append("若TA否认紧迫危险，尊重并继续陪伴，但本轮始终保持关注。");
            case DISTRESS -> directives.append("\n\n【姿态约束】对方情绪强度很高：以反映与陪伴为主，不要提问，不要急于给方法，")
                    .append("先让情绪落地。");
            case NONE -> { }
        }

        // 持续关注：历史里有消极意念而当前已缓和——不当作过去，但也不反复提起。
        if (historicTier.isSafetyRelevant() && currentTier.ordinal() < historicTier.ordinal()) {
            directives.append("\n\n【持续关注（系统规则）】用户在最近几轮表达过消极意念，虽然本轮语气缓和了，")
                    .append("仍要继续保持关注：自然地确认TA现在的状态与安全，不要当作话题已经过去；")
                    .append("也不要反复重提造成压力——一次轻柔的确认就够。");
        }
        return directives.toString();
    }
}
