package com.dk.dkaiagent.integration.aiworker;

import com.dk.dkaiagent.orchestration.ExecutionContextScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class AiWorkerClient {

    private static final String PLAN_PATH = "/internal/v1/plan";
    private static final String REFINE_PATH = "/internal/v1/evidence/refine";
    private static final String CONSOLIDATE_PATH = "/internal/v1/memory/consolidate";
    private static final String RECALL_PATH = "/internal/v1/memory/recall";

    private final AiWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Semaphore bulkhead;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong();

    @Autowired
    public AiWorkerClient(AiWorkerProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    AiWorkerClient(AiWorkerProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.bulkhead = new Semaphore(properties.getMaxConcurrency(), true);
        if (properties.isEnabled() && properties.getSharedSecret().isBlank()) {
            // 空密钥时 worker 端鉴权为空操作，任何能绑定 sidecar 端口的进程都可伪造召回片段。
            log.warn("AI worker shared secret is blank; worker calls are unauthenticated. "
                    + "Set AI_WORKER_SHARED_SECRET in any non-local deployment.");
        }
    }

    public Optional<AiWorkerContracts.PlanResponse> plan(AiWorkerContracts.PlanRequest request) {
        Optional<AiWorkerContracts.PlanResponse> response = post(
                PLAN_PATH, request, AiWorkerContracts.PlanResponse.class, request.requestId());
        if (response.isEmpty()
                || !validEnvelope(request.requestId(), response.get().contractVersion(), response.get().requestId())) {
            return Optional.empty();
        }
        recordSemanticOutcome(request.requestId(), response.get().degraded());
        return response;
    }

    public Optional<AiWorkerContracts.RefineResponse> refine(AiWorkerContracts.RefineRequest request) {
        Optional<AiWorkerContracts.RefineResponse> response = post(
                REFINE_PATH, request, AiWorkerContracts.RefineResponse.class, request.requestId());
        if (response.isEmpty()
                || !validEnvelope(request.requestId(), response.get().contractVersion(), response.get().requestId())) {
            return Optional.empty();
        }
        recordSemanticOutcome(request.requestId(), response.get().degraded());
        return response;
    }

    public Optional<AiWorkerContracts.ConsolidateResponse> consolidate(AiWorkerContracts.ConsolidateRequest request) {
        Optional<AiWorkerContracts.ConsolidateResponse> response = post(
                CONSOLIDATE_PATH, request, AiWorkerContracts.ConsolidateResponse.class, request.requestId());
        if (response.isEmpty()
                || !validEnvelope(request.requestId(), response.get().contractVersion(), response.get().requestId())) {
            return Optional.empty();
        }
        recordSemanticOutcome(request.requestId(), response.get().degraded());
        return response;
    }

    public Optional<AiWorkerContracts.RecallResponse> recall(AiWorkerContracts.RecallRequest request) {
        Optional<AiWorkerContracts.RecallResponse> response = post(
                RECALL_PATH, request, AiWorkerContracts.RecallResponse.class, request.requestId());
        if (response.isEmpty()
                || !validEnvelope(request.requestId(), response.get().contractVersion(), response.get().requestId())) {
            return Optional.empty();
        }
        recordSemanticOutcome(request.requestId(), response.get().degraded());
        return response;
    }

    public Availability availability() {
        return new Availability(
                properties.isEnabled(),
                isCircuitOpen(),
                consecutiveFailures.get(),
                properties.getMaxConcurrency() - bulkhead.availablePermits()
        );
    }

    private <T> Optional<T> post(String path, Object payload, Class<T> responseType, String requestId) {
        if (!properties.isEnabled() || isCircuitOpen()) {
            return Optional.empty();
        }
        if (!bulkhead.tryAcquire()) {
            log.warn("AI worker bulkhead is full; using local agent fallback");
            return Optional.empty();
        }

        // X-Request-Id 直接取自请求体的 requestId：worker 契约要求 header==body 恒等。
        // 从 ExecutionContextScope 推导会在 scope 未绑定的线程（如异步整合）上产出 "unbound"，
        // 与体内的真实 UUID 不一致而被 worker 以 HTTP 400 拒绝。
        try {
            Duration timeout = effectiveTimeout();
            if (timeout.isZero()) {
                return Optional.empty();
            }
            byte[] json = objectMapper.writeValueAsBytes(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json));
            if (!properties.getSharedSecret().isBlank()) {
                builder.header("X-AI-Worker-Token", properties.getSharedSecret());
            }

            HttpResponse<byte[]> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                recordFailure(requestId, "http_" + response.statusCode());
                return Optional.empty();
            }
            T decoded = objectMapper.readValue(response.body(), responseType);
            return Optional.ofNullable(decoded);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            recordFailure(requestId, "interrupted");
            return Optional.empty();
        } catch (Exception error) {
            recordFailure(requestId, error.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            bulkhead.release();
        }
    }

    private Duration effectiveTimeout() {
        Duration configured = Duration.ofSeconds(properties.getRequestTimeoutSeconds());
        return ExecutionContextScope.current()
                .map(context -> context.remaining().compareTo(configured) < 0
                        ? context.remaining() : configured)
                .orElse(configured);
    }

    private URI resolve(String path) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    private boolean validEnvelope(String expectedRequestId, String version, String actualRequestId) {
        boolean valid = AiWorkerContracts.VERSION.equals(version) && expectedRequestId.equals(actualRequestId);
        if (!valid) {
            recordFailure(expectedRequestId, "contract_envelope_invalid");
        }
        return valid;
    }

    private void recordSemanticOutcome(String requestId, boolean degraded) {
        if (degraded) {
            recordFailure(requestId, "semantic_degraded");
        } else {
            consecutiveFailures.set(0);
        }
    }

    private boolean isCircuitOpen() {
        long openUntil = circuitOpenUntilMillis.get();
        if (openUntil == 0) {
            return false;
        }
        if (System.currentTimeMillis() < openUntil) {
            return true;
        }
        if (circuitOpenUntilMillis.compareAndSet(openUntil, 0)) {
            consecutiveFailures.set(0);
        }
        return false;
    }

    private void recordFailure(String requestId, String failureType) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getFailureThreshold()) {
            circuitOpenUntilMillis.set(System.currentTimeMillis()
                    + Duration.ofSeconds(properties.getCircuitOpenSeconds()).toMillis());
        }
        log.warn("AI worker call failed; requestId={}, failureType={}, consecutiveFailures={}",
                requestId, failureType, failures);
    }

    public record Availability(boolean enabled, boolean circuitOpen, int consecutiveFailures, int activeCalls) {}
}
