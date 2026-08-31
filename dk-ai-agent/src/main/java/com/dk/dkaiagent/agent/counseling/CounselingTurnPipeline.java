package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import jakarta.annotation.Resource;
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

    public Flux<CounselingStreamEvent> run(CounselingTurnRequest request) {
        return Flux.defer(() -> {
            // 归档收口：深度模式原先在 executor 内归档、快速模式在 doChatWithRagByStream 内归档，
            // 两条路径各自维护一份"先落库"逻辑。统一到此处后，幂等键只需穿透这一个调用点。
            counselingApp.prepareConversationTurn(
                    request.ownerId(), request.chatId(), request.message(), request.clientMsgId());
            return request.deepThinking()
                    ? counselingAgentExecutor.prepareAndAnswer(
                            request.message(), request.chatId(), request.ownerId())
                    : standardAnswer(request.ownerId(), request.message(), request.chatId());
        });
    }

    /** 标准 RAG 作答（轮次已归档）。delta/done 的字段值与历史 SSE 契约逐字段一致。 */
    private Flux<CounselingStreamEvent> standardAnswer(long ownerId, String message, String chatId) {
        return counselingApp.doChatWithRagByStreamPrepared(ownerId, message, chatId)
                .map(chunk -> "[DONE]".equals(chunk)
                        ? CounselingStreamEvent.done("standard", false)
                        : CounselingStreamEvent.delta(chunk, "standard", false));
    }
}
