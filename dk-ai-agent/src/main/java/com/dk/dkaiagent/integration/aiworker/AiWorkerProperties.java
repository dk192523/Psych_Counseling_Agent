package com.dk.dkaiagent.integration.aiworker;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@ConfigurationProperties(prefix = "app.ai-worker")
public class AiWorkerProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8001";
    private int connectTimeoutSeconds = 2;
    private int requestTimeoutSeconds = 25;
    private int maxConcurrency = 4;
    private int failureThreshold = 3;
    private int circuitOpenSeconds = 30;
    private String sharedSecret = "";

    @PostConstruct
    void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.ai-worker.base-url must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("app.ai-worker.base-url must be a valid URI", error);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("app.ai-worker.base-url must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("app.ai-worker.base-url must include a host");
        }
        requirePositive(connectTimeoutSeconds, "connect-timeout-seconds");
        requirePositive(requestTimeoutSeconds, "request-timeout-seconds");
        requirePositive(maxConcurrency, "max-concurrency");
        requirePositive(failureThreshold, "failure-threshold");
        requirePositive(circuitOpenSeconds, "circuit-open-seconds");
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("app.ai-worker." + name + " must be greater than zero");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int value) { this.connectTimeoutSeconds = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { this.requestTimeoutSeconds = value; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public int getCircuitOpenSeconds() { return circuitOpenSeconds; }
    public void setCircuitOpenSeconds(int circuitOpenSeconds) { this.circuitOpenSeconds = circuitOpenSeconds; }
    public String getSharedSecret() { return sharedSecret; }
    public void setSharedSecret(String sharedSecret) { this.sharedSecret = sharedSecret == null ? "" : sharedSecret; }
}
