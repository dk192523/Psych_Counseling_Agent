package com.dk.dkaiagent.memory;

/**
 * 风险分级（对话内核 v2）：把原来"命中/不命中"的二元危机判定拆成四级，
 * 每级对应不同的对话姿态——分级的目的不是给用户贴标签，是让安全资源的投入强度匹配风险。
 *
 * <p>判定只看当前这条用户消息（确定性词面规则），优先级 IMMINENT &gt; PASSIVE &gt; DISTRESS：
 * 一条消息同时含手段与意念时按更重的算。</p>
 *
 * <ul>
 *   <li>{@link #NONE}：普通对话；</li>
 *   <li>{@link #DISTRESS}：强情绪困扰（撑不住/崩溃/好累…）——不是安全问题，只要求回应姿态
 *       转为倾听与陪伴（worker 侧 response_mode=listen 的 Java 同源信号）；</li>
 *   <li>{@link #PASSIVE}：被动消极意念（不想活/想死/死了算了…）——普通响应链继续，
 *       但本轮必须注入安全姿态：确认被听见、一次温和的安全询问、资源提示；</li>
 *   <li>{@link #IMMINENT}：紧迫危险（手段/计划/进行中：吞药、割腕、跳楼、"正在被打"…）——
 *       绕过一切 LLM 聊天链路，直接走专用危机响应模板。</li>
 * </ul>
 */
public enum RiskTier {
    NONE,
    DISTRESS,
    PASSIVE,
    IMMINENT;

    /** 安全相关（需要落安全备注/触发安全姿态）：PASSIVE 与 IMMINENT。 */
    public boolean isSafetyRelevant() {
        return this == PASSIVE || this == IMMINENT;
    }
}
