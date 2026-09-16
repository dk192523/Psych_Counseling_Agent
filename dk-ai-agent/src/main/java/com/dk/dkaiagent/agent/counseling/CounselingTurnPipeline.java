package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.app.SafetyOutputGuard;
import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyProperties;
import com.dk.dkaiagent.memory.SafetyTerms;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 聊天轮次编排的唯一入口：快速与深度两条链路在此汇合。
 *
 * <p>职责收口为三件事：① 用户消息归档每轮恰好发生一次（幂等键在此透传）；② 按模式分流——
 * 深度走 {@link CounselingAgentExecutor} 的 plan→retrieve→grade→answer，快速走 Java RAG 链；
 * ③ 两条链路的事件统一为 {@link CounselingStreamEvent}，SSE 事件序列与 phase 名保持不变。</p>
 *
 * <p>分流后的作答一律使用 {@code *Prepared} 变体（轮次已归档），深度降级到标准链时
 * 因此不会把同一句话写两次。</p>
 */
@Component
public class CounselingTurnPipeline {

    private static final Logger auditLog = LoggerFactory.getLogger(CounselingTurnPipeline.class);

    /**
     * @param ownerId     会话归属用户 id，由控制器在开流前完成所有权校验后传入
     * @param clientMsgId 前端为本轮生成的幂等键，可空（旧客户端不携带时后端不去重）
     * @param deepThinking true 走深度链路，false 走标准 RAG 链路
     */
    public record CounselingTurnRequest(
            long ownerId,
            String chatId,
            String message,
            String clientMsgId,
            boolean deepThinking) {
    }

    @Resource
    private CounselingApp counselingApp;

    @Resource
    private CounselingAgentExecutor counselingAgentExecutor;

    @Resource
    private CrisisResponse crisisResponse;

    @Resource
    private SafetyProperties safetyProperties;

    public Flux<CounselingStreamEvent> run(CounselingTurnRequest request) {
        return Flux.defer(() -> {
            // 危机拦截先于一切：IMMINENT 级（手段/计划/进行中）直接走专用模板，
            // 不进入任何 LLM 聊天链——这是快速模式此前缺失的危机前置检查，
            // 现在两条模式在 pipeline 收口处统一获得。
            RiskTier tier = SafetyTerms.assess(request.message());
            if (tier.isSafetyRelevant()) {
                // A2 审计：只记分级/会话/主体/模式，绝不记正文（隐私红线）。
                // IMMINENT=WARN 便于日志告警直接筛出；PASSIVE=INFO 留痕不告警。
                if (tier == RiskTier.IMMINENT) {
                    auditLog.warn("安全事件 tier=IMMINENT chatId={} ownerId={} deep={}",
                            request.chatId(), request.ownerId(), request.deepThinking());
                } else {
                    auditLog.info("安全事件 tier=PASSIVE chatId={} ownerId={} deep={}",
                            request.chatId(), request.ownerId(), request.deepThinking());
                }
            }
            counselingApp.prepareConversationTurn(
                    request.ownerId(), request.chatId(), request.message(), request.clientMsgId());
            if (tier == RiskTier.IMMINENT && safetyProperties.isCrisisResponseEnabled()) {
                return crisisResponse(request);
            }
            Flux<CounselingStreamEvent> events = request.deepThinking()
                    ? counselingAgentExecutor.prepareAndAnswer(
                            request.message(), request.chatId(), request.ownerId())
                    : standardAnswer(request.ownerId(), request.message(), request.chatId());
            // PASSIVE/IMMINENT 轮次的输出侧检查：模型若应和自伤意念或漏给资源，流尾补一条纠正与资源。
            return SafetyOutputGuard.guard(events, tier, crisisResponse.supplement());
        });
    }

    /** 危机模板响应：用户消息已归档，模板流式发出，回答照常归档并触发记忆整合。 */
    private Flux<CounselingStreamEvent> crisisResponse(CounselingTurnRequest request) {
        String text = crisisResponse.render();
        counselingApp.archiveAssistantAnswer(request.ownerId(), request.chatId(), text);
        return Flux.fromIterable(crisisResponse.toChunks(text))
                .map(chunk -> CounselingStreamEvent.delta(chunk, "standard", false))
                .concatWithValues(CounselingStreamEvent.done("standard", false));
    }

    /** 标准 RAG 作答（轮次已归档）。delta/done 的字段值与历史 SSE 契约逐字段一致。 */
    private Flux<CounselingStreamEvent> standardAnswer(long ownerId, String message, String chatId) {
        return counselingApp.doChatWithRagByStreamPrepared(ownerId, message, chatId)
                .map(chunk -> "[DONE]".equals(chunk)
                        ? CounselingStreamEvent.done("standard", false)
                        : CounselingStreamEvent.delta(chunk, "standard", false));
    }
}
