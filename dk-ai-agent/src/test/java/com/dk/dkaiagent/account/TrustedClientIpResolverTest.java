package com.dk.dkaiagent.account;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class TrustedClientIpResolverTest {
    private MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }

    @Test void untrustedSocketPeerCannotSpoofForwardedHeaders() {
        var resolver = new TrustedClientIpResolver("10.0.0.2");
        var request = request("192.0.2.7", "198.51.100.8");
        request.addHeader("Forwarded", "for=198.51.100.9");
        request.addHeader("X-Real-IP", "198.51.100.10");
        assertEquals("192.0.2.7", resolver.resolve(request));
    }

    @Test void defaultTrustsNoProxyEvenOnLoopback() {
        assertEquals("127.0.0.1", new TrustedClientIpResolver("").resolve(request("127.0.0.1", "198.51.100.8")));
    }

    @Test void trustedProxyUsesClosestUntrustedHopAndIgnoresSpoofedPrefix() {
        var resolver = new TrustedClientIpResolver("10.0.0.2,10.0.0.3");
        assertEquals("192.0.2.7", resolver.resolve(request("10.0.0.2", "198.51.100.99, 192.0.2.7, 10.0.0.3")));
    }

    @Test void malformedOrOversizedChainFallsBackToSocketPeer() {
        var resolver = new TrustedClientIpResolver("10.0.0.2");
        for (String xff : new String[]{"attacker.example", "", "192.0.2.1, ", "x".repeat(2049)}) {
            assertEquals("10.0.0.2", resolver.resolve(request("10.0.0.2", xff)));
        }
    }

    @Test void hostnamesAndWildcardTrustAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedClientIpResolver("localhost"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedClientIpResolver("10.0.0.0/8"));
    }
}
