package com.dk.dkaiagent.integration.aiworker;

import com.dk.dkaiagent.orchestration.ExecutionContextScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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
import java.util.function.BooleanSupplier;

@Component
@Slf4j
public class AiWorkerClient {

    private static final String PLAN_PATH = "/internal/v1/plan";
    private static final String REFINE_PATH = "/internal/v1/evidence/refine";
    private static final String CONSOLIDATE_PATH = "/internal/v1/memory/consolidate";
    private static final String RECALL_PATH = "/internal/v1/memory/recall";
    private static final String DESKTOP_MANAGED_PROPERTY = "app.ai-worker.desktop-managed";
    private static final String PROCESS_ALIVE_PROPERTY = "app.ai-worker.process-alive";

    private final AiWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Environment environment;
    private final Semaphore bulkhead;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong();

    public AiWorkerClient(AiWorkerProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, (Environment) null);
    }

    @Autowired
    public AiWorkerClient(AiWorkerProperties properties, ObjectMapper objectMapper, Environment environment) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build(), environment);
    }

    AiWorkerClient(AiWorkerProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this(properties, objectMapper, httpClient, null);
    }

    AiWorkerClient(AiWorkerProperties properties, ObjectMapper objectMapper,
                   HttpClient httpClient, Environment environment) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.environment = environment;
        this.bulkhead = new Semaphore(properties.getMaxConcurrency(), true);
        if (properties.isEnabled() && properties.getSharedSecret().isBlank()) {
            // Worker 已改为 fail-closed：空密钥不再是"调用免鉴权通过"，而是每次调用被 401 挡回，
            // 于是整个 Python 智能面静默退到 Java 兜底——功能看着还在，质量降级却不报错。
            // 这条 WARN 是唯一的线索，措辞必须指向真实症状，否则运维会去查网络而不是查配置。
            log.warn("AI worker shared secret is blank; every worker call will be rejected with 401 "
                    + "and the intelligence plane will silently fall back to the Java path. "
                    + "Set AI_WORKER_SHARED_SECRET to match the worker's own value.");
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
            if (!desktopWorkerAvailable()) {
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

            // 序列化期间 Worker 也可能退出；发送前再次检查，避免正文进入复用端口的进程。
            if (!desktopWorkerAvailable()) {
                return Optional.empty();
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

    private boolean desktopWorkerAvailable() {
        try {
            if (environment == null
                    || !environment.getProperty(DESKTOP_MANAGED_PROPERTY, Boolean.class, false)) {
                return true;
            }
            // 读取原始对象，不能把配置字符串转换成进程存活判据。
            Object gate = environment.getProperty(PROCESS_ALIVE_PROPERTY, Object.class);
            if (gate instanceof BooleanSupplier processAlive && processAlive.getAsBoolean()) {
                return true;
            }
        } catch (RuntimeException error) {
            // 不记录异常内容、请求标识或正文：门禁实现的异常也必须 fail-closed。
            log.warn("Desktop AI worker process gate failed; using local agent fallback");
            return false;
        }
        log.warn("Desktop AI worker process is unavailable; using local agent fallback");
        return false;
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
