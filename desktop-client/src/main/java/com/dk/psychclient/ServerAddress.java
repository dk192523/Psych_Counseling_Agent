package com.dk.psychclient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** A validated server root. Validation never resolves DNS or connects to a server. */
public final class ServerAddress {
    private final URI root;

    private ServerAddress(URI root) {
        this.root = root;
    }

    public static ServerAddress parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("请输入服务端根地址。");
        }
        try {
            URI supplied = new URI(input.strip());
            String scheme = supplied.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("服务端地址必须使用 http 或 https。");
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            String host = supplied.getHost();
            if (supplied.isOpaque() || host == null || host.isBlank() || supplied.getRawUserInfo() != null) {
                throw new IllegalArgumentException("请输入有效域名或 IP 地址，地址中不能包含账号或密码。");
            }
            if (supplied.getRawQuery() != null || supplied.getRawFragment() != null
                    || !(supplied.getRawPath().isEmpty() || supplied.getRawPath().equals("/"))) {
                throw new IllegalArgumentException("请输入根地址，不要附加页面路径、查询参数或锚点。");
            }
            host = host.toLowerCase(Locale.ROOT);
            int port = supplied.getPort();
            if (port != -1 && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException("端口必须在 1 到 65535 之间。");
            }
            if (scheme.equals("http") && !isLoopback(host)) {
                throw new IllegalArgumentException("远程服务必须使用 HTTPS；HTTP 仅允许本机回环地址。");
            }
            if (port == defaultPort(scheme)) port = -1;
            return new ServerAddress(new URI(scheme, null, host, port, "/", null, null));
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("服务端地址格式不正确。", failure);
        }
    }

    private static boolean isLoopback(String host) {
        String literal = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (literal.equals("localhost") || literal.equals("::1") || literal.equals("0:0:0:0:0:0:0:1")) return true;
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) return false;
        for (String octet : octets) {
            // Reject noncanonical numeric forms: browsers may interpret them differently.
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static int defaultPort(String scheme) {
        return scheme.equalsIgnoreCase("https") ? 443 : 80;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
    }

    public URI root() {
        return root;
    }

    public URI login() {
        return root.resolve("/login");
    }

    public boolean allowsNavigation(String destination) {
        try {
            URI uri = new URI(destination);
            return uri.getRawUserInfo() == null && uri.getHost() != null
                    && root.getScheme().equalsIgnoreCase(uri.getScheme())
                    && root.getHost().equalsIgnoreCase(uri.getHost())
                    && effectivePort(root) == effectivePort(uri);
        } catch (URISyntaxException | NullPointerException failure) {
            return false;
        }
    }

    @Override
    public String toString() {
        return root.toASCIIString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServerAddress address && root.equals(address.root);
    }

    @Override
    public int hashCode() {
        return Objects.hash(root);
    }
}
