package com.dk.psychclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Only loopback fake servers; these tests never launch Spring or contact a remote server. */
class ClientUiHostTest {
    private final HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private HttpServer upstream;
    private ExecutorService fakeWorkers;
    private ClientUiHost host;
    private String cookie;
    private final AtomicInteger upstreamCalls = new AtomicInteger();
    private final AtomicReference<FakeHandler> handler = new AtomicReference<>();

    @BeforeEach void setUp() throws Exception {
        fakeWorkers = Executors.newFixedThreadPool(4);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        upstream.setExecutor(fakeWorkers);
        handler.set(exchange -> json(exchange, 200, "{\"ok\":true}"));
        upstream.createContext("/", exchange -> {
            upstreamCalls.incrementAndGet();
            try { handler.get().handle(exchange); } finally { exchange.close(); }
        });
        upstream.start();
        host = new ClientUiHost(ServerAddress.parse("http://127.0.0.1:" + upstream.getAddress().getPort()));
        host.start();
        cookie = redeem(host);
    }

    @AfterEach void cleanUp() {
        if (host != null) host.close();
        if (upstream != null) upstream.stop(0);
        if (fakeWorkers != null) fakeWorkers.shutdownNow();
        browser.close();
    }

    @Test void healthAcceptsReadyServerWithVersionAndCompatibleProtocol() throws Exception {
        AtomicReference<String> seenPath = new AtomicReference<>();
        for (String version : List.of("dev", "2.4.1", "3.0.0-rc.1+build.7")) {
            handler.set(exchange -> {
                seenPath.set(exchange.getRequestURI().getPath());
                json(exchange, 200, "{\"status\":\"UP\",\"serverVersion\":\"" + version
                        + "\",\"protocolVersion\":" + LocalServerEndpoint.PROTOCOL_VERSION + "}");
            });
            assertDoesNotThrow(host::checkUpstreamHealth, version);
            assertEquals("/api/system/health", seenPath.get());
        }
        assertEquals(3, upstreamCalls.get());
    }

    @Test void healthRejectsMissingEmptyOrBlankServerVersion() {
        for (String versionField : List.of("", ",\"serverVersion\":\"\"", ",\"serverVersion\":\"   \"")) {
            handler.set(exchange -> json(exchange, 200, "{\"status\":\"UP\",\"protocolVersion\":"
                    + LocalServerEndpoint.PROTOCOL_VERSION + versionField + "}"));
            IOException failure = assertThrows(IOException.class, host::checkUpstreamHealth, versionField);
            assertFalse(failure instanceof ClientUiHost.ProtocolMismatchException);
        }
        assertEquals(3, upstreamCalls.get());
    }

    @Test void healthRejectsMissingOrIncompatibleProtocolWithDistinctException() {
        for (String protocolField : List.of("", ",\"protocolVersion\":"
                + (LocalServerEndpoint.PROTOCOL_VERSION + 1))) {
            handler.set(exchange -> json(exchange, 200,
                    "{\"status\":\"UP\",\"serverVersion\":\"dev\"" + protocolField + "}"));
            assertThrows(ClientUiHost.ProtocolMismatchException.class, host::checkUpstreamHealth, protocolField);
        }
        assertEquals(2, upstreamCalls.get());
    }

    @Test void randomLoopbackOriginAndOneTimeBootstrap() throws Exception {
        assertEquals("127.0.0.1", host.origin().getHost());
        assertTrue(host.origin().getPort() > 0);
        assertEquals(host.origin(), host.start());
        assertEquals(host.origin().resolve("/"), host.homeUri());
        assertEquals(403, browser.send(HttpRequest.newBuilder(host.bootstrapUri()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, request("GET", "/api/auth/csrf", false).statusCode());
        assertEquals(200, request("GET", "/api/auth/csrf", true).statusCode());
        try (ClientUiHost second = new ClientUiHost(ServerAddress.parse(
                "http://127.0.0.1:" + upstream.getAddress().getPort()))) {
            second.start();
            assertNotEquals(host.origin(), second.origin());
            assertNotEquals(host.bootstrapUri().getRawQuery(), second.bootstrapUri().getRawQuery());
            String secondCookie = redeem(second);
            assertNotEquals(cookie.split("=", 2)[0], secondCookie.split("=", 2)[0]);
            assertEquals(200, browser.send(HttpRequest.newBuilder(second.origin().resolve("/api/auth/csrf"))
                    .header("Cookie", secondCookie).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, browser.send(HttpRequest.newBuilder(second.origin().resolve("/api/auth/csrf"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void rejectsForeignOriginRefererFetchMetadataAndCorsPreflight() throws Exception {
        for (String header : List.of("Origin", "Referer")) {
            assertEquals(403, browser.send(api("/api/auth/me").header(header, "https://evil.example/").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(403, browser.send(api("/api/auth/me").header("Sec-Fetch-Site", "cross-site").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, browser.send(api("/api/auth/me").header("Sec-Fetch-Mode", "navigate").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> preflight = browser.send(api("/api/auth/me").header("Origin", "https://evil.example")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, preflight.statusCode());
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertEquals(200, browser.send(api("/api/auth/me").header("Origin", host.origin().toString())
                .header("Referer", host.homeUri().toString()).header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, upstreamCalls.get());
    }

    @Test void hostMustBeUniqueExactAndConnectUpgradeAreRejected() throws Exception {
        String authority = host.origin().getRawAuthority();
        assertEquals(403, raw("GET", "/api/auth/me", "Host: evil.example\r\n"));
        assertEquals(403, raw("GET", "/api/auth/me", "Host: " + authority + "\r\nHost: evil.example\r\n"));
        assertEquals(403, raw("GET", "/api/auth/me", "Host: " + authority + "\r\nUpgrade: websocket\r\n"));
        assertEquals(405, raw("CONNECT", "/api/auth/me", "Host: " + authority + "\r\n"));
        assertEquals(0, upstreamCalls.get());
    }

    @Test void pathsAreExactAndCannotReplaceAuthority() throws Exception {
        String authority = host.origin().getRawAuthority();
        assertFalse(ClientUiHost.validPath("//evil.example/api/auth/me"));
        for (String path : List.of("/api/../auth/me", "/api/%2e%2e/auth/me", "/api/%252e%252e/auth/me",
                "/api/auth%2fme", "/api/auth\\me", "//evil.example/api/auth/me", "/api/auth/%00me",
                "http://evil.example/api/auth/me")) {
            assertTrue(raw("GET", path, "Host: " + authority + "\r\n") >= 400, path);
        }
        for (String path : List.of("/api/auth/me/", "/api/auth/meevil", "/api/actuator/env", "/api/proxy"))
            assertEquals(405, request("GET", path, true).statusCode(), path);
        assertEquals(405, request("GET", "/api/ai/counseling/chat/sse", true).statusCode());
        assertEquals(405, request("PUT", "/api/auth/login", true).statusCode());
        assertEquals(404, request("GET", "/unknown-vue-route", false).statusCode());
        assertEquals(0, upstreamCalls.get());
        AtomicReference<String> seenTarget = new AtomicReference<>();
        handler.set(exchange -> { seenTarget.set(exchange.getRequestURI().toASCIIString()); json(exchange, 200, "{}"); });
        assertEquals(200, request("GET", "/api/admin/users?keyword=https%3A%2F%2Fevil.example", true).statusCode());
        assertEquals("/api/admin/users?keyword=https%3A%2F%2Fevil.example", seenTarget.get());
    }

    @Test void cookieJarRotatesSessionAndBrowserNeverReceivesUpstreamCookie() throws Exception {
        AtomicReference<String> upstreamCookie = new AtomicReference<>();
        AtomicReference<String> csrf = new AtomicReference<>();
        handler.set(exchange -> {
            upstreamCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            csrf.set(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
            assertNull(exchange.getRequestHeaders().getFirst("Origin"));
            assertNull(exchange.getRequestHeaders().getFirst("Referer"));
            assertNull(exchange.getRequestHeaders().getFirst("Forwarded"));
            assertNull(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            if (exchange.getRequestURI().getPath().equals("/api/auth/csrf"))
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=before; Path=/api; HttpOnly");
            if (exchange.getRequestURI().getPath().equals("/api/auth/login"))
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=after; Path=/api; HttpOnly");
            exchange.getResponseHeaders().add("Set-Cookie", "secureCookie=secret; Path=/api; Secure; HttpOnly");
            json(exchange, 200, "{}");
        });
        HttpResponse<String> first = request("GET", "/api/auth/csrf", true);
        assertNull(upstreamCookie.get());
        assertTrue(first.headers().allValues("Set-Cookie").isEmpty());
        HttpResponse<String> login = browser.send(api("/api/auth/login")
                .header("Origin", host.origin().toString()).header("Referer", host.homeUri().toString())
                .header("Forwarded", "for=evil").header("X-Forwarded-For", "8.8.8.8")
                .header("X-CSRF-TOKEN", "csrf-value").header("Cookie", "JSESSIONID=browser-injected")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(login.headers().allValues("Set-Cookie").isEmpty());
        assertTrue(upstreamCookie.get().contains("JSESSIONID=before"));
        assertFalse(upstreamCookie.get().contains("browser-injected"));
        assertFalse(upstreamCookie.get().contains(cookie.split("=", 2)[0]));
        assertFalse(upstreamCookie.get().contains("secureCookie"));
        assertEquals("csrf-value", csrf.get());
        request("GET", "/api/auth/me", true);
        assertTrue(upstreamCookie.get().contains("JSESSIONID=after"));
        assertFalse(upstreamCookie.get().contains("JSESSIONID=before"));
    }

    @Test void preservesJsonErrorsButRejectsRedirectsHtmlAndOversizedBodies() throws Exception {
        handler.set(exchange -> json(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}"));
        HttpResponse<String> unauthorized = request("GET", "/api/auth/me", true);
        assertEquals(401, unauthorized.statusCode());
        assertEquals("{\"code\":\"UNAUTHORIZED\"}", unauthorized.body());
        handler.set(exchange -> { exchange.getResponseHeaders().set("Location", "https://evil.example"); exchange.sendResponseHeaders(302, -1); });
        HttpResponse<String> redirect = request("GET", "/api/auth/me", true);
        assertEquals(502, redirect.statusCode());
        assertTrue(redirect.headers().firstValue("Location").isEmpty());
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 4); exchange.getResponseBody().write("evil".getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(502, request("GET", "/api/auth/me", true).statusCode());
        int before = upstreamCalls.get();
        assertEquals(413, browser.send(api("/api/auth/login").POST(HttpRequest.BodyPublishers.ofByteArray(
                new byte[2 * 1024 * 1024 + 1])).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(before, upstreamCalls.get());
    }

    @Test void sseIsFlushedBeforeCompletionAndCancelClosesUpstream() throws Exception {
        CountDownLatch firstSent = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        handler.set(exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/api/ai/counseling/chat/sse")) {
                json(exchange, 200, "{}");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            firstSent.countDown();
            try { allowFinish.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        String id = "stream_request_id_123456";
        CompletableFuture<HttpResponse<InputStream>> pending = browser.sendAsync(api("/api/ai/counseling/chat/sse")
                .header(ClientUiHost.REQUEST_ID_HEADER, id).POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        try {
            assertTrue(firstSent.await(3, TimeUnit.SECONDS));
            HttpResponse<InputStream> response = pending.get(3, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals(id, response.headers().firstValue(ClientUiHost.REQUEST_ID_HEADER).orElseThrow());
            try (InputStream input = response.body()) {
                assertEquals("data: first\n\n", new String(input.readNBytes(13), StandardCharsets.UTF_8));
                assertEquals(403, request("POST", ClientUiHost.CANCEL_PREFIX + id, false).statusCode());
                assertEquals(204, request("POST", ClientUiHost.CANCEL_PREFIX + id, true).statusCode());
                CompletableFuture<Integer> eof = CompletableFuture.supplyAsync(() -> {
                    try { return input.read(); } catch (IOException disconnected) { return -1; }
                });
                assertEquals(-1, eof.get(3, TimeUnit.SECONDS));
            }
            assertEquals(200, request("GET", "/api/auth/me", true).statusCode());
        } finally { allowFinish.countDown(); }
    }

    @Test void silentSseCanBeCancelledWithoutAnyEventAndReleasesSocket() throws Exception {
        host.close();
        try (ServerSocket fake = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch responseHeadersSent = new CountDownLatch(1);
            CompletableFuture<Boolean> disconnected = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = fake.accept()) {
                    socket.setSoTimeout(5000);
                    consumeRequest(socket);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    responseHeadersSent.countDown();
                    return socket.getInputStream().read() == -1;
                } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            host = new ClientUiHost(ServerAddress.parse("http://127.0.0.1:" + fake.getLocalPort()));
            host.start(); cookie = redeem(host);
            String id = "silent_stream_request_12345";
            CompletableFuture<HttpResponse<InputStream>> response = browser.sendAsync(api("/api/ai/counseling/chat/sse")
                    .header(ClientUiHost.REQUEST_ID_HEADER, id).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertTrue(responseHeadersSent.await(3, TimeUnit.SECONDS));
            assertEquals(204, request("POST", ClientUiHost.CANCEL_PREFIX + id, true).statusCode());
            assertTrue(disconnected.get(3, TimeUnit.SECONDS));
            try (InputStream input = response.get(3, TimeUnit.SECONDS).body()) { assertEquals(-1, input.read()); }
            assertEquals(404, request("POST", ClientUiHost.CANCEL_PREFIX + id, true).statusCode());
        }
    }

    @Test void failedPostIsSentExactlyOnceAndErrorDoesNotLeakDestination() throws Exception {
        host.close();
        try (ServerSocket fake = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger accepts = new AtomicInteger();
            CompletableFuture<Void> connection = CompletableFuture.runAsync(() -> {
                try (Socket socket = fake.accept()) { accepts.incrementAndGet(); consumeRequest(socket); }
                catch (IOException failure) { throw new RuntimeException(failure); }
            });
            host = new ClientUiHost(ServerAddress.parse("http://127.0.0.1:" + fake.getLocalPort()));
            host.start(); cookie = redeem(host);
            HttpResponse<String> response = request("POST", "/api/auth/login", true);
            assertEquals(502, response.statusCode());
            assertFalse(response.body().contains("127.0.0.1"));
            assertFalse(response.body().contains(Integer.toString(fake.getLocalPort())));
            connection.get(3, TimeUnit.SECONDS);
            assertEquals(1, accepts.get());
            fake.setSoTimeout(250);
            assertThrows(java.net.SocketTimeoutException.class, fake::accept);
        }
    }

    @Test void closingClearsUpstreamSessionAndCancelsOnlyOwnedResources() throws Exception {
        handler.set(exchange -> { exchange.getResponseHeaders().set("Set-Cookie", "JSESSIONID=old; Path=/api; HttpOnly"); json(exchange, 200, "{}"); });
        request("GET", "/api/auth/csrf", true);
        host.close();
        assertThrows(IllegalStateException.class, host::start);
        AtomicReference<String> seenCookie = new AtomicReference<>();
        handler.set(exchange -> { seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie")); json(exchange, 200, "{}"); });
        host = new ClientUiHost(ServerAddress.parse("http://127.0.0.1:" + upstream.getAddress().getPort()));
        host.start(); cookie = redeem(host);
        assertEquals(200, request("GET", "/api/auth/me", true).statusCode());
        assertNull(seenCookie.get());
        assertEquals(200, browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + upstream.getAddress().getPort() + "/api/system/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void routeAllowlistCoversOnlyCurrentControllerMethods() {
        for (String path : List.of("/api/auth/csrf", "/api/auth/me", "/api/system/health",
                "/api/health", "/api/ai/conversations", "/api/ai/conversations/chat_123",
                "/api/exam/questions", "/api/admin/users", "/api/admin/stats"))
            assertTrue(ClientApiRoutes.allows("GET", path), path);
        for (String path : List.of("/api/auth/login", "/api/auth/register", "/api/auth/logout",
                "/api/users/me/password", "/api/ai/conversations", "/api/ai/counseling/chat/sync",
                "/api/ai/counseling/chat/sse", "/api/exam/questions/q_123/answer", "/api/admin/users/bulk",
                "/api/admin/users/12/disable", "/api/admin/users/12/enable", "/api/admin/users/12/password-reset"))
            assertTrue(ClientApiRoutes.allows("POST", path), path);
        assertTrue(ClientApiRoutes.allows("DELETE", "/api/ai/conversations/chat_123"));
        assertTrue(ClientApiRoutes.allows("DELETE", "/api/admin/users/12"));
        assertFalse(ClientApiRoutes.allows("GET", "/api/ai/counseling/chat/sse"));
        assertFalse(ClientApiRoutes.allows("POST", "/api/admin/users/12"));
        assertFalse(ClientApiRoutes.allows("DELETE", "/api/admin/users/bulk"));
        assertFalse(ClientApiRoutes.allows("GET", "/api/ai/conversations/../auth"));
        assertFalse(ClientApiRoutes.allows("POST", "/api/admin/users/0/enable"));
    }

    private HttpRequest.Builder api(String path) {
        return HttpRequest.newBuilder(host.origin().resolve(path)).timeout(Duration.ofSeconds(5)).header("Cookie", cookie);
    }
    private HttpResponse<String> request(String method, String path, boolean authorized) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(host.origin().resolve(path)).timeout(Duration.ofSeconds(5));
        if (authorized) request.header("Cookie", cookie);
        return browser.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String redeem(ClientUiHost target) throws Exception {
        HttpResponse<String> response = browser.send(HttpRequest.newBuilder(target.bootstrapUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode());
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertFalse(header.contains("Domain="));
        assertEquals("/login", response.headers().firstValue("Location").orElseThrow());
        return header.split(";", 2)[0];
    }
    private int raw(String method, String target, String headers) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", host.origin().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\n" + headers
                    + "Cookie: " + cookie + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String firstLine = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(firstLine.substring(9, 12));
        }
    }
    private static void consumeRequest(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int value = input.read();
            if (value == -1) throw new IOException("Early EOF");
            matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
        }
    }
    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @FunctionalInterface private interface FakeHandler { void handle(HttpExchange exchange) throws IOException; }
}
