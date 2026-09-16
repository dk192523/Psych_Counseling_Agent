package com.dk.dkaiagent.app;

import com.dk.dkaiagent.memory.SafetyProperties;
import jakarta.annotation.Resource;
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
