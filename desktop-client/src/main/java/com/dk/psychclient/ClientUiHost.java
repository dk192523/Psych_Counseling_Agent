package com.dk.psychclient;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A loopback-only UI and capability-protected proxy for exactly one server.
 * Load bootstrapUri() once in the WebView, then use homeUri(). Never expose the
 * bootstrap URI to remote pages or logs. close() cancels only this host's sockets.
 */
public final class ClientUiHost implements AutoCloseable {
    public static final String REQUEST_ID_HEADER = "X-Client-Request-Id";
    public static final String CANCEL_PREFIX = "/__client/cancel/";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Set<String> PAGES = Set.of("/", "/login", "/psych-master", "/practice", "/admin");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "DELETE");
    private static final Set<String> FORWARD_HEADERS = Set.of("accept", "content-type", "x-csrf-token");
    private final ClientUpstreamTransport transport;
    private final String bootstrap = secret();
    private final String capability = secret();
    // Loopback cookies are shared across ports, so each host needs a distinct name.
    private final String capabilityCookie = "psychclient-capability-" + secret();
    private final AtomicBoolean redeemed = new AtomicBoolean();
    private final Semaphore proxySlots = new Semaphore(8);
    private final Map<String, ClientUpstreamTransport.Call> active = new ConcurrentHashMap<>();
    private HttpServer server;
    private ThreadPoolExecutor workers;
    private volatile URI origin;
    private volatile boolean closed;

    public ClientUiHost(ServerAddress upstream) throws IOException {
        transport = new ClientUpstreamTransport(Objects.requireNonNull(upstream));
    }

    /** Binds an OS-assigned port on literal IPv4 loopback. A closed host cannot restart. */
    public synchronized URI start() throws IOException {
        if (closed) throw new IllegalStateException("Host is closed");
        if (server != null) return origin;
        HttpServer local = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(
                new byte[]{127, 0, 0, 1}), 0), 32);
        workers = new ThreadPoolExecutor(12, 12, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), task -> {
                    Thread thread = new Thread(task, "client-ui-proxy");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        origin = URI.create("http://127.0.0.1:" + local.getAddress().getPort());
        local.setExecutor(workers);
        local.createContext("/", this::handle);
        local.start();
        server = local;
        return origin;
    }

    public URI origin() { return requireOrigin(); }
    public URI homeUri() { return requireOrigin().resolve("/"); }
    public URI bootstrapUri() { return requireOrigin().resolve("/__client/bootstrap?token=" + bootstrap); }

    /** Readiness uses the same pinned destination, TLS checks, cookies and no-redirect transport as API traffic. */
    public void checkUpstreamHealth() throws IOException {
        if (closed) throw new IOException("Client is closed");
        String id = secret();
        try (ClientUpstreamTransport.Call call = new ClientUpstreamTransport.Call()) {
            active.put(id, call);
            try {
                if (closed) throw new IOException("Client is closed");
                // A silent health endpoint must not hold the lifecycle worker for the SSE read timeout.
                CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(call::close);
                ClientUpstreamTransport.Response response = transport.execute("GET", "/api/system/health", null,
                        Map.of("Accept", "application/json"), new byte[0], call);
                String type = ClientUpstreamTransport.single(response.headers(), "content-type");
                String encoding = ClientUpstreamTransport.single(response.headers(), "content-encoding");
                if (response.status() != 200 || type == null
                        || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")
                        || encoding != null && !encoding.equalsIgnoreCase("identity")) {
                    throw new IOException("Health response rejected");
                }
                byte[] bytes = response.body().readNBytes(4097);
                if (bytes.length > 4096) throw new IOException("Health response too large");
                Map<String, String> health = healthFields(new String(bytes, StandardCharsets.UTF_8));
                if (!"UP".equals(health.get("status"))) throw new IOException("Server is not ready");
                if (!Integer.toString(LocalServerEndpoint.PROTOCOL_VERSION).equals(health.get("protocolVersion"))) {
                    throw new ProtocolMismatchException();
                }
                // Presence identifies the complete contract; release names may be dev or any semantic version.
                String serverVersion = health.get("serverVersion");
                if (serverVersion == null || serverVersion.isBlank()) {
                    throw new IOException("Missing server version in health response");
                }
            } finally { active.remove(id, call); }
        }
    }

    /** The health contract is a flat JSON object with string or integer fields. Reject ambiguous/duplicate fields. */
    private static Map<String, String> healthFields(String json) throws IOException {
        String text = "(?:[^\\\"\\\\\\x00-\\x1F]|\\\\(?:[\\\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*";
        String member = "\"(" + text + ")\"\\s*:\\s*(?:\"(" + text + ")\"|(0|[1-9][0-9]*))";
        if (!Pattern.compile("\\s*\\{\\s*(?:" + member + "(?:\\s*,\\s*" + member + ")*)?\\s*}\\s*")
                .matcher(json).matches()) throw new IOException("Invalid health response");
        var matcher = Pattern.compile(member).matcher(json);
        Map<String, String> fields = new LinkedHashMap<>();
        while (matcher.find()) {
            // Expected keys and values are emitted without escapes by the server contract.
            String value = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
            if (fields.putIfAbsent(matcher.group(1), value) != null) throw new IOException("Duplicate health field");
        }
        return fields;
    }

    public static final class ProtocolMismatchException extends IOException {
        private ProtocolMismatchException() { super("Server protocol is incompatible"); }
    }

    private URI requireOrigin() {
        URI current = origin;
        if (current == null || closed) throw new IllegalStateException("Host is not running");
        return current;
    }

    private void handle(HttpExchange exchange) {
        try {
            securityHeaders(exchange.getResponseHeaders());
            if (closed) { error(exchange, 503, "CLIENT_CLOSED"); return; }
            if (!validHeaders(exchange.getRequestHeaders()) || !validHost(exchange)
                    || exchange.getRequestHeaders().containsKey("Upgrade")
                    || headerContains(exchange, "Connection", "upgrade")) {
                error(exchange, 403, "REQUEST_REJECTED"); return;
            }
            if (!METHODS.contains(exchange.getRequestMethod())) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                    || !validPath(path) || !validQuery(query)) {
                error(exchange, 400, "INVALID_PATH"); return;
            }
            boolean api = path.startsWith("/api/") || path.startsWith(CANCEL_PREFIX);
            if (!validSource(exchange, api)) { error(exchange, 403, "SOURCE_REJECTED"); return; }
            if (path.equals("/__client/bootstrap")) { bootstrap(exchange, query); return; }
            if (api) {
                if (!hasCapability(exchange)) { error(exchange, 403, "CAPABILITY_REQUIRED"); return; }
                if (path.startsWith(CANCEL_PREFIX)) { cancel(exchange, path, query); return; }
                if (!ClientApiRoutes.allows(exchange.getRequestMethod(), path)) {
                    error(exchange, 405, "ROUTE_NOT_ALLOWED"); return;
                }
                proxy(exchange, path, query);
            } else {
                if (!Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
                    error(exchange, 405, "METHOD_NOT_ALLOWED"); return;
                }
                serveUi(exchange, path, query);
            }
        } catch (RequestTooLarge failure) {
            if (exchange.getResponseCode() == -1) try { error(exchange, 413, "REQUEST_TOO_LARGE"); } catch (IOException ignored) { }
        } catch (IOException | RuntimeException failure) {
            if (exchange.getResponseCode() == -1) try { error(exchange, 502, "UPSTREAM_UNAVAILABLE"); } catch (IOException ignored) { }
        } finally { exchange.close(); }
    }

    private boolean validHost(HttpExchange exchange) {
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        return hosts != null && hosts.size() == 1 && origin.getRawAuthority().equals(hosts.getFirst());
    }

    private boolean validSource(HttpExchange exchange, boolean api) {
        Headers headers = exchange.getRequestHeaders();
        List<String> origins = headers.get("Origin");
        if (origins != null && (origins.size() != 1 || !origin.toString().equals(origins.getFirst()))) return false;
        List<String> referers = headers.get("Referer");
        if (referers != null) {
            if (referers.size() != 1) return false;
            try {
                URI ref = URI.create(referers.getFirst());
                if (ref.getRawUserInfo() != null || !origin.getScheme().equals(ref.getScheme())
                        || !origin.getRawAuthority().equals(ref.getRawAuthority())) return false;
            } catch (IllegalArgumentException invalid) { return false; }
        }
        List<String> sites = headers.get("Sec-Fetch-Site");
        if (sites != null && (sites.size() != 1 || !Set.of("same-origin", "none").contains(sites.getFirst()))) return false;
        List<String> modes = headers.get("Sec-Fetch-Mode");
        if (modes != null && (modes.size() != 1 || !(api ? Set.of("cors", "same-origin")
                : Set.of("cors", "same-origin", "no-cors", "navigate")).contains(modes.getFirst()))) return false;
        List<String> destinations = headers.get("Sec-Fetch-Dest");
        return !api || destinations == null || (destinations.size() == 1 && destinations.getFirst().equals("empty"));
    }

    private void bootstrap(HttpExchange exchange, String query) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
        if (query == null || !query.startsWith("token=") || !equalSecret(bootstrap, query.substring(6))
                || !redeemed.compareAndSet(false, true)) { error(exchange, 403, "BOOTSTRAP_REJECTED"); return; }
        exchange.getResponseHeaders().set("Set-Cookie", capabilityCookie + "=" + capability
                + "; Path=/; HttpOnly; SameSite=Strict");
        exchange.getResponseHeaders().set("Location", "/login");
        exchange.sendResponseHeaders(302, -1);
    }

    private boolean hasCapability(HttpExchange exchange) {
        if (!redeemed.get()) return false;
        int matches = 0;
        for (String cookieHeader : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String cookie : cookieHeader.split(";")) {
                String[] pair = cookie.strip().split("=", 2);
                if (pair[0].equals(capabilityCookie)) {
                    if (pair.length != 2 || !equalSecret(capability, pair[1])) return false;
                    matches++;
                }
            }
        }
        return matches == 1;
    }

    private void cancel(HttpExchange exchange, String path, String query) throws IOException {
        String id = path.substring(CANCEL_PREFIX.length());
        if (!exchange.getRequestMethod().equals("POST") || query != null || !validId(id)) {
            error(exchange, 400, "INVALID_CANCEL"); return;
        }
        readBody(exchange);
        ClientUpstreamTransport.Call call = active.get(id);
        if (call != null) call.close();
        exchange.sendResponseHeaders(call == null ? 404 : 204, -1);
    }

    private void proxy(HttpExchange exchange, String path, String query) throws IOException {
        if (!proxySlots.tryAcquire()) { error(exchange, 503, "CLIENT_BUSY"); return; }
        String id = null;
        ClientUpstreamTransport.Call call = new ClientUpstreamTransport.Call();
        try {
            List<String> ids = exchange.getRequestHeaders().get(REQUEST_ID_HEADER);
            id = ids == null ? secret() : (ids.size() == 1 ? ids.getFirst() : "");
            if (!validId(id)) { error(exchange, 400, "INVALID_REQUEST_ID"); return; }
            if (active.putIfAbsent(id, call) != null) { error(exchange, 409, "REQUEST_ID_IN_USE"); return; }
            if (closed) { call.close(); error(exchange, 503, "CLIENT_CLOSED"); return; }
            byte[] body = readBody(exchange);
            if (exchange.getRequestMethod().equals("GET") && body.length != 0) {
                error(exchange, 400, "INVALID_BODY"); return;
            }
            Map<String, String> forwarded = new LinkedHashMap<>();
            for (String name : FORWARD_HEADERS) {
                List<String> values = exchange.getRequestHeaders().get(name);
                if (values != null) {
                    if (values.size() != 1) { error(exchange, 400, "INVALID_HEADERS"); return; }
                    forwarded.put(name, values.getFirst());
                }
            }
            ClientUpstreamTransport.Response response = transport.execute(exchange.getRequestMethod(), path, query, forwarded, body, call);
            if (response.status() >= 300 && response.status() < 400 && response.status() != 304) {
                error(exchange, 502, "UPSTREAM_RESPONSE_REJECTED"); return;
            }
            String type = ClientUpstreamTransport.single(response.headers(), "content-type");
            String encoding = ClientUpstreamTransport.single(response.headers(), "content-encoding");
            if ((encoding != null && !encoding.equalsIgnoreCase("identity"))
                    || (type != null && !allowedType(type))) {
                error(exchange, 502, "UPSTREAM_RESPONSE_REJECTED"); return;
            }
            if (type == null && response.status() != 204 && response.status() != 304) {
                // Missing types may hide upstream HTML. Only an actually empty body is safe.
                if (response.body().read() != -1) { error(exchange, 502, "UPSTREAM_RESPONSE_REJECTED"); return; }
            }
            if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set(REQUEST_ID_HEADER, id);
            String retryAfter = ClientUpstreamTransport.single(response.headers(), "retry-after");
            if (retryAfter != null && retryAfter.matches("[0-9]{1,8}")) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            boolean noBody = response.status() == 204 || response.status() == 304 || type == null;
            exchange.sendResponseHeaders(response.status(), noBody ? -1 : 0);
            if (!noBody) {
                byte[] buffer = new byte[8192];
                for (int count; (count = response.body().read(buffer)) != -1;) {
                    exchange.getResponseBody().write(buffer, 0, count);
                    // Flush every upstream read; SSE must reach the WebView before EOF.
                    exchange.getResponseBody().flush();
                }
            }
        } finally {
            if (id != null) active.remove(id, call);
            call.close();
            proxySlots.release();
        }
    }

    private void serveUi(HttpExchange exchange, String path, String query) throws IOException {
        if (path.startsWith("/__client/") || path.equals("/api")) { error(exchange, 404, "NOT_FOUND"); return; }
        String resource = PAGES.contains(path) ? "/static/index.html" : "/static" + path;
        URL url = ClientUiHost.class.getResource(resource);
        // Never use the filesystem or server-supplied content as UI assets.
        if (url == null || !url.getProtocol().equals("jar") || path.endsWith("/" ) && !PAGES.contains(path)) {
            error(exchange, 404, "NOT_FOUND"); return;
        }
        String type = staticType(resource);
        if (type == null) { error(exchange, 404, "NOT_FOUND"); return; }
        try (InputStream content = url.openStream()) {
            exchange.getResponseHeaders().set("Content-Type", type);
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(200, head ? -1 : 0);
            if (!head) content.transferTo(exchange.getResponseBody());
        }
    }

    private static String staticType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html" -> "text/html; charset=UTF-8";
            case "js" -> "text/javascript; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "json", "map" -> "application/json; charset=UTF-8";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "webp" -> "image/webp";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            default -> null;
        };
    }

    static boolean validPath(String path) {
        if (path == null || path.length() > 2048 || !path.matches("/[A-Za-z0-9/_.-]*") || path.contains("//")) return false;
        for (String part : path.split("/", -1)) if (part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static boolean validQuery(String query) {
        if (query == null) return true;
        if (query.length() > 4096) return false;
        for (int i = 0; i < query.length(); i++) {
            char value = query.charAt(i);
            if (value < 33 || value > 126 || value == '\\') return false;
            if (value == '%') {
                if (i + 2 >= query.length()) return false;
                int high = Character.digit(query.charAt(++i), 16), low = Character.digit(query.charAt(++i), 16);
                if (high == -1 || low == -1) return false;
                int decoded = high * 16 + low;
                if (decoded < 32 || decoded == 127 || decoded == '\\' || decoded == '%') return false;
            }
        }
        return true;
    }

    private static boolean validHeaders(Headers headers) {
        int size = 0;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            size += entry.getKey().length();
            for (String value : entry.getValue()) {
                size += value.length();
                if (value.chars().anyMatch(c -> c < 32 || c > 126)) return false;
            }
        }
        return size <= 32_768;
    }

    private static boolean headerContains(HttpExchange exchange, String name, String token) {
        for (String value : exchange.getRequestHeaders().getOrDefault(name, List.of()))
            for (String part : value.split(",")) if (part.strip().equalsIgnoreCase(token)) return true;
        return false;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        List<String> lengths = exchange.getRequestHeaders().get("Content-Length");
        if (lengths != null && (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,10}")
                || Long.parseLong(lengths.getFirst()) > MAX_BODY)) throw new RequestTooLarge();
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new RequestTooLarge();
        return body;
    }

    private static boolean allowedType(String type) {
        String media = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return media.equals("application/json") || media.equals("application/problem+json")
                || media.equals("text/event-stream") || media.equals("text/plain");
    }

    private static boolean validId(String id) { return id.matches("[A-Za-z0-9_-]{16,80}"); }
    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean equalSecret(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
    private static void securityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; "
                + "frame-ancestors 'none'; form-action 'self'");
    }
    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        byte[] json = ("{\"code\":\"" + code + "\",\"message\":\"请求未完成，请检查连接后重试。\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(status, -1);
        else { exchange.sendResponseHeaders(status, json.length); exchange.getResponseBody().write(json); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        active.values().forEach(ClientUpstreamTransport.Call::close);
        transport.close();
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
        active.clear();
    }

    private static final class RequestTooLarge extends IOException { }
}
