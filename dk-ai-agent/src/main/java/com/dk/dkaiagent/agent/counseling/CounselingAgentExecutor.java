package com.dk.dkaiagent.agent.counseling;

import reactor.core.publisher.Flux;

/**
 * Provider-neutral deep counseling agent contract.
 * A Hermes sidecar can implement the same contract without changing the HTTP or Vue layers.
 */
public interface CounselingAgentExecutor {

    Flux<CounselingStreamEvent> stream(String message, String chatId, long ownerId);
}
