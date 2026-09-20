package com.dk.dkaiagent.config;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Rejects declared multi-replica operation before any application beans are initialized. */
public final class DeploymentGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentGuard.class);
    private static final String UNSUPPORTED = "Only APP_DEPLOYMENT_MODE=single and APP_REPLICA_COUNT=1 "
            + "are supported. Multi-replica operation requires shared Session/revocation, global rate limiting, "
            + "turn lease/fencing and memory consolidation coordination. Overlapping rolling deployments "
            + "are prohibited; instance IDs are not distributed locks.";
    private final DeploymentProperties bootstrap;

    public DeploymentGuard(DeploymentProperties bootstrap) {
        this.bootstrap = bootstrap;
    }

    /** Called at the beginning of main, before SpringApplication.run and its startup side effects. */
    public static DeploymentProperties validateEnvironment(Map<String, String> environment) {
        return validate(environment.getOrDefault("APP_DEPLOYMENT_MODE", "single"),
                environment.getOrDefault("APP_REPLICA_COUNT", "1"), environment.get("APP_INSTANCE_ID"));
    }

    public static DeploymentProperties validate(String mode, String replicaCount, String instanceId) {
        // Exact values intentionally reject blanks, malformed numbers, negatives and overflow alike.
        if (!"single".equals(mode) || !"1".equals(replicaCount)) {
            throw new IllegalStateException(UNSUPPORTED);
        }
        String resolvedId = instanceId == null || instanceId.isBlank()
                ? UUID.randomUUID().toString() : instanceId;
        if (!resolvedId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalStateException("APP_INSTANCE_ID must contain 1..128 ASCII letters, digits, '.', '_', ':' or '-'.");
        }
        return new DeploymentProperties(mode, 1, resolvedId);
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        // ConfigData has already loaded YAML/profiles here; CLI/JVM overrides cannot bypass the guard.
        // This event precedes context creation, RUNNING-turn recovery and vector-store initialization.
        String configuredId = environment.getProperty("app.deployment.instance-id");
        DeploymentProperties resolved = validate(
                environment.getProperty("app.deployment.mode", bootstrap.mode()),
                environment.getProperty("app.deployment.replica-count", Integer.toString(bootstrap.replicaCount())),
                configuredId == null || configuredId.isBlank() ? bootstrap.instanceId() : configuredId);
        environment.getPropertySources().addFirst(new MapPropertySource("validatedDeployment", Map.of(
                "app.deployment.mode", resolved.mode(),
                "app.deployment.replica-count", resolved.replicaCount(),
                "app.deployment.instance-id", resolved.instanceId())));
        LOG.info("Deployment mode={}, replicas={}, instanceId={} (observation only; single replica required)",
                resolved.mode(), resolved.replicaCount(), resolved.instanceId());
    }
}
