package com.dk.dkaiagent.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionContextScopeTest {

    @Test
    void bindingIsImmutableAndDoesNotLeakAfterTheCall() {
        AgentRequestContext context = AgentRequestContext.deep("conversation-1", Duration.ofSeconds(5));

        assertTrue(ExecutionContextScope.current().isEmpty());
        String requestId = ExecutionContextScope.call(
                context,
                () -> ExecutionContextScope.requireCurrent().requestId());

        assertEquals(context.requestId(), requestId);
        assertTrue(ExecutionContextScope.current().isEmpty());
    }

    @Test
    void explicitWrapperPropagatesContextToVirtualThreadTask() throws Exception {
        AgentRequestContext context = AgentRequestContext.deep("conversation-2", Duration.ofSeconds(5));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String requestId = executor.submit(ExecutionContextScope.wrap(
                    context,
                    () -> ExecutionContextScope.requireCurrent().requestId())::get).get();

            assertEquals(context.requestId(), requestId);
        }
    }
}
