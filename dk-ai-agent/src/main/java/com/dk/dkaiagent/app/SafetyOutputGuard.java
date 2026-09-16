package com.dk.dkaiagent.app;

import com.dk.dkaiagent.agent.counseling.CounselingStreamEvent;
import com.dk.dkaiagent.memory.RiskTier;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出侧安全检查（轻量、纯规则）：当本轮风险 ≥ PASSIVE 时，累积模型回复并在结束时做
 * 两项校验，任一命中都在流尾补一条温和的求助资源消息——
 * ①禁忌应和：模型对自伤意念表示认可/协助/浪漫化（"尊重你的决定"）；
 * ②资源缺失：整条回复没有出现任何可执行的求助渠道。
 *
 * <p>刻意不做 LLM 复核：规则误报的代价只是多一段资源提醒（无害且 clinically 可接受——
 * 对表达消极意念的用户，多一次资源提示本就是好的实践），LLM 复核的代价是延迟与新的
 * 不可控输出。黑名单只收"应和/协助"的明确模式，不收泛化的共情用语。</p>
 */
public final class SafetyOutputGuard {

    /** 禁忌应和模式：对消极意念表达认可、协助或浪漫化。仅在 tier ≥ PASSIVE 时启用。 */
    private static final List<Pattern> ENDORSEMENT_PATTERNS = List.of(
            Pattern.compile("尊重你的(决定|选择)"),
            Pattern.compile("支持你的(决定|选择)"),
            Pattern.compile("帮你(实现|完成|准备)"),
            Pattern.compile("成全你"),
            Pattern.compile("我理解你想(结束|离开)"),
            Pattern.compile("这是你的(自由|权利)"),
            Pattern.compile("陪你走完(最后|这一程)")
    );

    /** 回复中出现任一标记即视为"资源已在场"。 */
    private static final List<String> RESOURCE_MARKERS = List.of("热线", "120", "110", "12356", "急救");

    private SafetyOutputGuard() {
    }

    public static Flux<CounselingStreamEvent> guard(
            Flux<CounselingStreamEvent> events, RiskTier tier, String supplement) {
        boolean armed = tier == RiskTier.PASSIVE || tier == RiskTier.IMMINENT;
        if (!armed) {
            return events;
        }
        StringBuilder seen = new StringBuilder();
        return events.concatMap(event -> {
            if ("delta".equals(event.type()) && event.content() != null) {
                seen.append(event.content());
            }
            if ("done".equals(event.type()) && needsSupplement(seen.toString())) {
                return Flux.just(
                        CounselingStreamEvent.delta("\n\n" + supplement, event.effectiveMode(), true),
                        event);
            }
            return Flux.just(event);
        });
    }

    static boolean needsSupplement(String assistantText) {
        if (assistantText == null) {
            return true;
        }
        if (violated(assistantText)) {
            return true;
        }
        return RESOURCE_MARKERS.stream().noneMatch(assistantText::contains);
    }

    static boolean violated(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return false;
        }
        return ENDORSEMENT_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(assistantText).find());
    }
}
