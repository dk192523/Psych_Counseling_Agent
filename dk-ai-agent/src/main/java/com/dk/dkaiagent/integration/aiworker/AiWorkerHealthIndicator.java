package com.dk.dkaiagent.integration.aiworker;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes degradation state without making the optional Python worker a hard
 * readiness dependency of the Java control plane.
 */
@Component("aiWorker")
public class AiWorkerHealthIndicator implements HealthIndicator {

    private final AiWorkerClient client;

    public AiWorkerHealthIndicator(AiWorkerClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        AiWorkerClient.Availability availability = client.availability();
        String mode = !availability.enabled()
                ? "disabled"
                : availability.circuitOpen() ? "java-fallback" : "python-preferred";
        return Health.up()
                .withDetail("mode", mode)
                .withDetail("circuitOpen", availability.circuitOpen())
                .withDetail("consecutiveFailures", availability.consecutiveFailures())
                .withDetail("activeCalls", availability.activeCalls())
                .build();
    }
}
