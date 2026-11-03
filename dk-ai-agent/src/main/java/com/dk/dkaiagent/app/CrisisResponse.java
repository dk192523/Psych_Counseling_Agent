package com.dk.dkaiagent.app;

import com.dk.dkaiagent.memory.SafetyProperties;
import com.dk.dkaiagent.memory.SafetyTerms;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * IMMINENT 级风险的专用危机响应：确定性模板流式返回，**不经任何 LLM**。
 *
 * <p>为什么不用模型生成：这一轮的成败标准不是"说得多体贴"，而是三条硬要求必须百分百出现——
 * 非评判的接住、直接的安全确认、可立即执行的求助资源。LLM 在这个压力场景下可能漏资源、
 * 可能加多余的过渡句，还多一段首字延迟；危机时刻这两样都最贵。模板的"机械感"用
 * 三套变体轮换来缓解，语气打磨在模板本身做足。</p>
 *
 * <p>资源表来自 {@link SafetyProperties}（可配置），文案固定附带"以当地公布为准"。</p>
 */
@Component
public class CrisisResponse {

    private static final List<String> TEMPLATES = List.of("""
            听到你这样说，我很在意你现在的安全。先停一下，别的事都可以等，我们只看一件事：**你现在安全吗？**

            如果这些念头很强烈，或者你身边已经有能伤害自己的东西——请现在就行动：
            - %s
            - 告诉身边任何一个你能信任的人，让TA现在就陪着你。

            你不需要一个人扛这些。等你愿意的时候，可以告诉我你现在在哪里、身边有没有人。
            """, """
            谢谢你把这句话说了出来——这需要勇气，我也不会评判你。现在最要紧的只有你的安全：**此刻你身边有能伤害自己的东西吗？**

            请先做这一步，把危险的东西放远一点，然后：
            - %s
            - 或者敲开一扇门：家人、朋友、室友，任何一个找得到的人。

            这些念头像浪潮，会涨也会退。先陪自己撑过这一浪。你现在在哪里？身边有人吗？
            """, """
            我听到了，我在这儿。先不聊别的，我只想知道一件事：**你现在的安全还好吗？**

            如果答案让你不确定，请立刻做这三件事里的任何一件：
            - %s
            - 去一个有人的地方，别让自己独处；
            - 把这件事告诉一个你信得过的人——哪怕只发一条消息。

            你已经撑了很久，但今天不需要再独自硬撑。告诉我你现在的状况，我陪着你。
            """);

    private final SafetyProperties safetyProperties;
    private final java.util.concurrent.atomic.AtomicLong variantCursor = new java.util.concurrent.atomic.AtomicLong();

    public CrisisResponse(SafetyProperties safetyProperties) {
        this.safetyProperties = safetyProperties;
    }

    /**
     * 渲染危机响应全文。变体按请求序号轮换（同一用户重试同一轮不会拿到同一段话，
     * 也避免被缓存/审计时看起来像机械复读）。
     */
    public String render() {
        String resources = String.join("；\n- ", safetyProperties.getHotlines());
        int index = (int) Math.floorMod(variantCursor.getAndIncrement(), TEMPLATES.size());
        return TEMPLATES.get(index).formatted(resources);
    }

    /** 按当前消息中的危险类别选择模板，避免把遭受暴力或伤人意图误写成自伤。 */
    public String render(String message) {
        return switch (SafetyTerms.crisisCategory(message)) {
            case DANGER_FROM_OTHERS -> """
                    我听到了，你现在正面临他人的危险。**你现在能安全地联系紧急服务或身边可信任的人吗？**

                    - 如果能在不增加危险的情况下离开，请到有人、能求助的安全地点；不要争辩、夺取武器或冒险穿过对方。
                    - 如果无法安全离开，请尽可能与对方保持距离，寻找能够遮挡或隔开的地方。
                    - 立即联系当地报警或紧急服务（以当地公布为准），说明你的位置和正在发生的危险；如有人受伤，也请联系当地急救。
                    - 在安全的前提下联系可信任的人，让对方帮助报警或寻找线下援助。

                    先以脱离眼前危险为重，回复我可以等到安全之后。
                    """;
            case HARM_TO_OTHERS -> """
                    我听到你现在有伤害他人的冲动。**先暂停接近对方，也先不要去找对方。**

                    - 放下手中的刀具或其他危险物并退开，不要随身携带；与危险物和对方保持距离。
                    - 立即联系一个可信任的人，直接告诉对方你担心自己会伤人，请对方协助寻找线下帮助。
                    - 如果你担心自己马上会行动，或已经有人受伤，请立即联系当地报警、急救或紧急服务（以当地公布为准），说明你的位置和风险。

                    你现在能先停下来，与对方和危险物分开吗？
                    """;
            case SELF_HARM -> render();
            case UNSPECIFIED -> "先确认你和身边人的安全。如果有眼前的危险，请立即联系当地报警、急救或紧急服务（以当地公布为准），并向可信任的人寻求线下帮助。";
        };
    }

    /**
     * 输出侧检查的补充文本：措辞做成"温和提醒"而不是"纠偏"——它在两种情况下出现：
     * 模型应和了自伤意念（此时它兼任纠偏），或模型忘了给资源（此时它兜底补上）。
     * 两种情形下多一次资源提示都是 clinically 正确的。
     */
    public String supplement() {
        String resources = String.join("；", safetyProperties.getHotlines());
        return "另外想让你知道：如果那些念头变得强烈，随时可以求助——" + resources
                + "。你不需要一个人扛，我在这儿，你可以继续说。";
    }

    /** 输出检查及无模型配置时也沿用当前消息的危险类别。 */
    public String supplement(String message) {
        return supplement(message, false);
    }

    /** 历史风险只保留了等级时，补充中性求助资源，不推断当前存在自伤意图。 */
    public String supplement(String message, boolean historicalRisk) {
        return switch (SafetyTerms.crisisCategory(message)) {
            case DANGER_FROM_OTHERS, HARM_TO_OTHERS -> render(message);
            case UNSPECIFIED -> historicalRisk
                    ? render(message) + "\n\n如果你需要心理支持，也可以联系："
                            + String.join("；", safetyProperties.getHotlines())
                            + "（以当地公布为准）。"
                    : render(message);
            case SELF_HARM -> supplement();
        };
    }

    /**
     * 按段落切成 SSE delta。不能按固定字数切——模板里的 Markdown 粗体（**…**）一旦被
     * 切在两个 delta 中间，前端渲染就会漏出孤立的星号。
     */
    public List<String> toChunks(String text) {
        List<String> chunks = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n\n")) {
            if (!paragraph.isBlank()) {
                chunks.add(paragraph.trim() + "\n\n");
            }
        }
        return chunks;
    }
}
