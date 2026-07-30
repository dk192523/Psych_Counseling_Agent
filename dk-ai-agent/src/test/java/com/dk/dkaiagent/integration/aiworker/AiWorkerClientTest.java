package com.dk.dkaiagent.integration.aiworker;

import com.dk.dkaiagent.orchestration.AgentRequestContext;
import com.dk.dkaiagent.orchestration.ExecutionContextScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWorkerClientTest {

    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicBoolean semanticDegraded = new AtomicBoolean();
    private final AtomicInteger memoryStatus = new AtomicInteger(200);
    private final AtomicReference<String> memoryResponseRequestId = new AtomicReference<>("req-1");
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestIdHeader = new AtomicReference<>();
    private final AtomicReference<String> tokenHeader = new AtomicReference<>();
    private HttpServer server;
    private AiWorkerProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/plan", this::handlePlan);
        server.createContext("/internal/v1/memory/consolidate", this::handleConsolidate);
        server.createContext("/internal/v1/memory/recall", this::handleRecall);
        server.start();

        properties = new AiWorkerProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setSharedSecret("test-secret");
        properties.setRequestTimeoutSeconds(2);
        properties.setFailureThreshold(2);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsUtf8ContractAndCorrelationHeaders() throws Exception {
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());
        AiWorkerContracts.PlanRequest request = request("我最近总是失眠");
        AgentRequestContext context = new AgentRequestContext(
                "req-1", "conversation-1", java.time.Instant.now().plusSeconds(3), "deep");

        Optional<AiWorkerContracts.PlanResponse> result = ExecutionContextScope.call(
                context, () -> client.plan(request));

        assertTrue(result.isPresent());
        assertEquals("我最近总是失眠", new ObjectMapper()
                .readTree(requestBody.get()).get("currentMessage").asText());
        assertEquals("req-1", requestIdHeader.get());
        assertEquals("test-secret", tokenHeader.get());
        assertEquals(1, requestCount.get());
        assertFalse(client.availability().circuitOpen());
    }

    @Test
    void opensCircuitAndSkipsFurtherRemoteCallsAfterThreshold() {
        responseStatus.set(503);
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        assertTrue(client.plan(request("第一次")).isEmpty());
        assertTrue(client.plan(request("第二次")).isEmpty());
        assertTrue(client.availability().circuitOpen());

        assertTrue(client.plan(request("熔断后")).isEmpty());
        assertEquals(2, requestCount.get());
    }

    @Test
    void degradedResponsesOpenCircuitAndSkipFurtherRemoteCalls() {
        semanticDegraded.set(true);
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        assertTrue(client.plan(request("第一次")).orElseThrow().degraded());
        assertTrue(client.plan(request("第二次")).orElseThrow().degraded());
        assertTrue(client.availability().circuitOpen());

        assertTrue(client.plan(request("熔断后")).isEmpty());
        assertEquals(2, requestCount.get());
    }

    @Test
    void consolidateReturnsDigestAndSendsContractHeaders() throws Exception {
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());
        AiWorkerContracts.ConsolidateRequest request = consolidateRequest();
        AgentRequestContext context = new AgentRequestContext(
                "req-1", "conversation-1", java.time.Instant.now().plusSeconds(3), "deep");

        Optional<AiWorkerContracts.ConsolidateResponse> result = ExecutionContextScope.call(
                context, () -> client.consolidate(request));

        assertTrue(result.isPresent());
        assertEquals("req-1", result.get().requestId());
        assertEquals("用户近两周持续失眠。安全备注：用户提到想伤害自己的念头。", result.get().digest());
        assertFalse(result.get().degraded());
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertEquals("旧摘要", body.get("existingDigest").asText());
        assertEquals("我最近有想伤害自己的念头", body.get("messages").get(0).get("content").asText());
        assertTrue(body.get("messages").get(0).get("safetyRelevant").asBoolean());
        assertEquals(1200, body.get("limits").get("maxDigestChars").asInt());
        assertEquals("req-1", requestIdHeader.get());
        assertEquals("test-secret", tokenHeader.get());
        assertFalse(client.availability().circuitOpen());
    }

    @Test
    void consolidateSendsBodyRequestIdAsHeaderWhenExecutionContextScopeIsUnbound() throws Exception {
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        // 异步整合线程上 ExecutionContextScope 未绑定：X-Request-Id 必须取自请求体 requestId，
        // 否则 worker 的 header==body 契约会以 400 拒绝（mock 与 Python 端一致，不匹配即 400）。
        Optional<AiWorkerContracts.ConsolidateResponse> result = client.consolidate(consolidateRequest());

        assertTrue(result.isPresent(),
                "consolidate must succeed without a bound ExecutionContextScope");
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertEquals(body.get("requestId").asText(), requestIdHeader.get(),
                "X-Request-Id header must equal the body requestId");
        assertEquals("req-1", requestIdHeader.get());
        assertFalse(client.availability().circuitOpen());
    }

    @Test
    void consolidateReturnsEmptyOnHttpError() {
        memoryStatus.set(500);
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        assertTrue(client.consolidate(consolidateRequest()).isEmpty());
        assertEquals(1, client.availability().consecutiveFailures());
    }

    @Test
    void consolidateReturnsEmptyOnRequestIdMismatch() {
        memoryResponseRequestId.set("other-id");
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        assertTrue(client.consolidate(consolidateRequest()).isEmpty());
        assertEquals(1, client.availability().consecutiveFailures());
    }

    @Test
    void recallReturnsEpisodesAndSendsContractHeaders() throws Exception {
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());
        AiWorkerContracts.RecallRequest request = recallRequest();
        AgentRequestContext context = new AgentRequestContext(
                "req-1", "conversation-1", java.time.Instant.now().plusSeconds(3), "deep");

        Optional<AiWorkerContracts.RecallResponse> result = ExecutionContextScope.call(
                context, () -> client.recall(request));

        assertTrue(result.isPresent());
        assertEquals(1, result.get().episodes().size());
        AiWorkerContracts.RecallEpisode episode = result.get().episodes().get(0);
        assertEquals(7L, episode.id());
        assertEquals("user", episode.role());
        assertEquals("上周和妈妈吵架后一直睡不着", episode.snippet());
        assertEquals(0.82, episode.score(), 1e-9);
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertEquals("今晚又睡不着了", body.get("currentMessage").asText());
        assertEquals(7L, body.get("candidates").get(0).get("id").asLong());
        assertEquals(4, body.get("limits").get("maxEpisodes").asInt());
        assertEquals(300, body.get("limits").get("snippetMaxChars").asInt());
        assertEquals("req-1", requestIdHeader.get());
        assertEquals("test-secret", tokenHeader.get());
        assertFalse(client.availability().circuitOpen());
    }

    @Test
    void recallReturnsEmptyOnHttpError() {
        memoryStatus.set(502);
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        assertTrue(client.recall(recallRequest()).isEmpty());
        assertEquals(1, client.availability().consecutiveFailures());
    }

    @Test
    void recallReturnsEmptyOnRequestIdMismatch() {
        memoryResponseRequestId.set("other-id");
        AiWorkerClient client = new AiWorkerClient(properties, new ObjectMapper());

        assertTrue(client.recall(recallRequest()).isEmpty());
        assertEquals(1, client.availability().consecutiveFailures());
    }

    private AiWorkerContracts.PlanRequest request(String message) {
        return new AiWorkerContracts.PlanRequest(
                AiWorkerContracts.VERSION,
                "req-1",
                message,
                List.of(),
                new AiWorkerContracts.PlanLimits(3, 180, 5),
                "");
    }

    private AiWorkerContracts.ConsolidateRequest consolidateRequest() {
        return new AiWorkerContracts.ConsolidateRequest(
                AiWorkerContracts.VERSION,
                "req-1",
                "旧摘要",
                List.of(
                        new AiWorkerContracts.MemoryMessage("user", "我最近有想伤害自己的念头", true),
                        new AiWorkerContracts.MemoryMessage("assistant", "你现在身边有人陪着吗？", false)),
                new AiWorkerContracts.ConsolidateLimits(1200));
    }

    private AiWorkerContracts.RecallRequest recallRequest() {
        return new AiWorkerContracts.RecallRequest(
                AiWorkerContracts.VERSION,
                "req-1",
                "今晚又睡不着了",
                List.of("失眠 家庭冲突"),
                List.of(new AiWorkerContracts.RecallCandidate(7L, "user", "上周和妈妈吵架后一直睡不着", 0.9)),
                new AiWorkerContracts.RecallLimits(4, 300));
    }

    /**
     * Mirrors the frozen Python worker contract (main.py validate_request_id): a present
     * X-Request-Id header that differs from the body requestId is rejected with HTTP 400.
     */
    private boolean headerMatchesBodyRequestId(HttpExchange exchange, String body) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("X-Request-Id");
        String bodyRequestId = new ObjectMapper().readTree(body).get("requestId").asText();
        if (header != null && !header.equals(bodyRequestId)) {
            byte[] payload = "{\"detail\":\"request id mismatch\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(400, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
            return false;
        }
        return true;
    }

    private void handlePlan(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestIdHeader.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        tokenHeader.set(exchange.getRequestHeaders().getFirst("X-AI-Worker-Token"));
        if (!headerMatchesBodyRequestId(exchange, requestBody.get())) {
            return;
        }
        int status = responseStatus.get();
        String responseJson = semanticDegraded.get() ? """
                {"contractVersion":"1","requestId":"req-1","stage":"clarification",
                 "shouldRetrieve":true,"focus":"睡眠影响","queries":["失眠 现实影响"],
                 "missingInformation":[],"engine":"heuristic","degraded":true,
                 "degradedReasons":["llm_timeout"],"durationMs":3}
                """ : """
                {"contractVersion":"1","requestId":"req-1","stage":"clarification",
                 "shouldRetrieve":true,"focus":"睡眠影响","queries":["失眠 现实影响"],
                 "missingInformation":[],"engine":"test","degraded":false,
                 "degradedReasons":[],"durationMs":3}
                """;
        byte[] body = status == 200
                ? responseJson.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void handleConsolidate(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestIdHeader.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        tokenHeader.set(exchange.getRequestHeaders().getFirst("X-AI-Worker-Token"));
        if (!headerMatchesBodyRequestId(exchange, requestBody.get())) {
            return;
        }
        int status = memoryStatus.get();
        String responseJson = """
                {"contractVersion":"1","requestId":"%s","digest":"用户近两周持续失眠。安全备注：用户提到想伤害自己的念头。",
                 "engine":"test","degraded":false,"degradedReasons":[],"durationMs":5}
                """.formatted(memoryResponseRequestId.get());
        byte[] body = status == 200
                ? responseJson.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void handleRecall(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestIdHeader.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        tokenHeader.set(exchange.getRequestHeaders().getFirst("X-AI-Worker-Token"));
        if (!headerMatchesBodyRequestId(exchange, requestBody.get())) {
            return;
        }
        int status = memoryStatus.get();
        String responseJson = """
                {"contractVersion":"1","requestId":"%s","episodes":[{"id":7,"role":"user",
                 "snippet":"上周和妈妈吵架后一直睡不着","score":0.82}],
                 "engine":"test","degraded":false,"degradedReasons":[],"durationMs":4}
                """.formatted(memoryResponseRequestId.get());
        byte[] body = status == 200
                ? responseJson.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
