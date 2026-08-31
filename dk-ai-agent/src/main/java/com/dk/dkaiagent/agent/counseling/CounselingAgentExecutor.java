package com.dk.dkaiagent.agent.counseling;

import reactor.core.publisher.Flux;

/**
 * Provider-neutral deep counseling agent contract.
 * A Hermes sidecar can implement the same contract without changing the HTTP or Vue layers.
 */
public interface CounselingAgentExecutor {

    /**
     * 已归档轮次的深度准备与作答：plan → retrieve → grade → answer。
     * 用户消息归档由 {@link CounselingTurnPipeline} 统一负责，实现方不得再次落库；
     * 降级到标准链时同样只使用 {@code *Prepared} 变体，避免重复归档。
     */
    Flux<CounselingStreamEvent> prepareAndAnswer(String message, String chatId, long ownerId);
}
