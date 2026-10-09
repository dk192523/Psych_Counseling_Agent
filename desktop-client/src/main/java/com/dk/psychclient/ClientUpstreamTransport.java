package com.dk.psychclient;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** One socket per request: no retries, redirects, pool replay, proxy selector or global settings. */
final class ClientUpstreamTransport implements AutoCloseable {
    // CounselingTurnPipeline allows 180 seconds; leave time for its terminal response.
    private static final int READ_TIMEOUT_MS = 210_000;
    private final URI root;
    private final InetAddress pinnedAddress;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private boolean closed;

    ClientUpstreamTransport(ServerAddress address) throws IOException {
        root = address.root();
        String host = root.getHost();
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) throw new IOException("Upstream unavailable");
        if (root.getScheme().equals("http")) {
            for (InetAddress candidate : resolved)
                if (!candidate.isLoopbackAddress()) throw new IOException("HTTP requires loopback");
        }
        // Do not resolve again while serving requests, including after DNS changes.
        pinnedAddress = resolved[0];
    }

    Response execute(String method, String path, String query, Map<String, String> headers,
                     byte[] body, Call call) throws IOException {
        // path is validated by the host. String append cannot replace the configured authority.
        URI target = URI.create(root.toASCIIString().substring(0, root.toASCIIString().length() - 1)
                + path + (query == null ? "" : "?" + query));
        if (!root.getRawAuthority().equals(target.getRawAuthority())
                || !root.getScheme().equals(target.getScheme())) throw new IOException("Invalid target");
        int port = root.getPort() == -1 ? (root.getScheme().equals("https") ? 443 : 80) : root.getPort();
        String host = root.getHost();
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        Socket plain = new Socket(Proxy.NO_PROXY);
        call.attach(plain);
        plain.connect(new InetSocketAddress(pinnedAddress, port), 8_000);
        plain.setSoTimeout(READ_TIMEOUT_MS);
        Socket socket = plain;
        if (root.getScheme().equals("https")) {
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, host, port, true);
            call.attach(tls);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            socket = tls;
        }
        StringBuilder request = new StringBuilder(method).append(' ').append(path);
        if (query != null) request.append('?').append(query);
        request.append(" HTTP/1.1\r\nHost: ").append(root.getRawAuthority())
                .append("\r\nConnection: close\r\nAccept-Encoding: identity\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        synchronized (cookies) {
            if (closed) throw new IOException("Closed");
            cookies.get(target, Map.of()).forEach((name, values) -> {
                if (name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Cookie2")) {
                    String value = String.join("; ", values.stream()
                            .filter(cookie -> cookie != null && !cookie.isBlank()).toList());
                    if (!value.isBlank()) request.append(name).append(": ").append(value).append("\r\n");
                }
            });
        }
        request.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), 8192);
        int[] budget = {32_768};
        String statusLine = line(input, budget);
        if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) throw new IOException("Invalid response");
        int status = Integer.parseInt(statusLine.substring(9, 12));
        if (status < 200) throw new IOException("Unsupported interim response");
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        for (String line; !(line = line(input, budget)).isEmpty();) {
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))
                throw new IOException("Invalid header");
            String value = line.substring(colon + 1).strip();
            if (value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("Invalid header");
            responseHeaders.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT),
                    ignored -> new ArrayList<>()).add(value);
        }
        synchronized (cookies) {
            if (!closed && !call.cancelled.get()) cookies.put(target, responseHeaders);
        }
        String transfer = single(responseHeaders, "transfer-encoding");
        String length = single(responseHeaders, "content-length");
        if (transfer != null && (length != null || !transfer.equalsIgnoreCase("chunked")))
            throw new IOException("Unsupported framing");
        InputStream content;
        if (status == 204 || status == 304) content = new LimitedInput(input, 0);
        else if (transfer != null) content = new ChunkInput(input);
        else if (length != null) {
            if (!length.matches("[0-9]{1,18}")) throw new IOException("Invalid length");
            content = new LimitedInput(input, Long.parseLong(length));
        } else content = input;
        return new Response(status, responseHeaders, content);
    }

    static String single(Map<String, List<String>> headers, String name) throws IOException {
        List<String> values = headers.get(name);
        if (values == null) return null;
        if (values.size() != 1) throw new IOException("Duplicate header");
        return values.getFirst();
    }

    private static String line(InputStream input, int[] budget) throws IOException {
        StringBuilder text = new StringBuilder();
        while (--budget[0] >= 0) {
            int value = input.read();
            if (value == -1) throw new EOFException();
            if (value == '\r') {
                if (--budget[0] < 0 || input.read() != '\n') throw new IOException("Invalid line");
                return text.toString();
            }
            if (value < 32 || value == 127) throw new IOException("Invalid line");
            text.append((char) value);
        }
        throw new IOException("Headers too large");
    }

    @Override public void close() {
        synchronized (cookies) { closed = true; cookies.getCookieStore().removeAll(); }
    }

    record Response(int status, Map<String, List<String>> headers, InputStream body) { }

    static final class Call implements AutoCloseable {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Socket socket;
        synchronized void attach(Socket socket) throws IOException {
            if (cancelled.get()) { socket.close(); throw new IOException("Cancelled"); }
            this.socket = socket;
        }
        @Override public synchronized void close() {
            cancelled.set(true);
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static final class LimitedInput extends InputStream {
        private final InputStream input;
        private long remaining;
        LimitedInput(InputStream input, long remaining) { this.input = input; this.remaining = remaining; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = input.read(buffer, offset, (int) Math.min(length, remaining));
            if (count == -1) throw new EOFException();
            remaining -= count;
            return count;
        }
    }

    private static final class ChunkInput extends InputStream {
        private final InputStream input;
        private long remaining;
        private boolean delimiter;
        private boolean ended;
        ChunkInput(InputStream input) { this.input = input; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (ended) return -1;
            if (remaining == 0) {
                if (delimiter && (input.read() != '\r' || input.read() != '\n')) throw new IOException("Invalid chunk");
                String size = line(input, new int[]{1024});
                int extension = size.indexOf(';');
                if (extension != -1) size = size.substring(0, extension);
                if (!size.matches("[0-9a-fA-F]{1,15}")) throw new IOException("Invalid chunk");
                remaining = Long.parseLong(size, 16);
                delimiter = true;
                if (remaining == 0) {
                    int[] budget = {8192};
                    while (!line(input, budget).isEmpty()) { /* Ignore trailers, never forward them. */ }
                    ended = true;
                    return -1;
                }
            }
            int count = input.read(buffer, offset, (int) Math.min(length, remaining));
            if (count == -1) throw new EOFException();
            remaining -= count;
            return count;
        }
    }
}
