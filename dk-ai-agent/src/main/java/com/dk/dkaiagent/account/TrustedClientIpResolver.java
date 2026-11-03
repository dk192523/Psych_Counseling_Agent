package com.dk.dkaiagent.account;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact IP allowlist; no DNS lookups or implicit private-network/first-hop trust. */
public final class TrustedClientIpResolver {
    private final Set<String> trustedProxies;

    public TrustedClientIpResolver(String configuredProxies) {
        trustedProxies = Arrays.stream(configuredProxies.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(value -> {
                    String ip = canonicalIp(value);
                    if (ip == null) throw new IllegalArgumentException("Trusted proxies must be literal IP addresses");
                    return ip;
                }).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String peer = canonicalIp(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!trustedProxies.contains(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 2048) return peer;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 16) return peer;
        String current = peer;
        // Stop at the closest untrusted hop; ignore any client-supplied prefix before it.
        for (int i = hops.length - 1; i >= 0 && trustedProxies.contains(current); i--) {
            String next = canonicalIp(hops[i].trim());
            if (next == null) return peer;
            current = next;
        }
        return current;
    }

    private static String canonicalIp(String value) {
        if (value == null || value.isBlank() || value.length() > 45) return null;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) return null;
            }
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
