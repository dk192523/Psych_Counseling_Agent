package com.dk.psychclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ServerAddressTest {
    @Test
    void normalizesSchemeHostDefaultPortAndLoginPath() {
        ServerAddress server = ServerAddress.parse("  HTTPS://COUNSELING.EXAMPLE:443/  ");
        assertEquals("https://counseling.example/", server.toString());
        assertEquals("https://counseling.example/login", server.login().toString());
        assertEquals(server, ServerAddress.parse("https://counseling.example"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080/",
            "http://127.23.4.5", "http://[::1]:8080", "http://[0:0:0:0:0:0:0:1]/"})
    void permitsHttpOnlyForLiteralLoopbackOrLocalhost(String input) {
        assertDoesNotThrow(() -> ServerAddress.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "counseling.example", "ftp://counseling.example",
            "file:///C:/login.html", "javascript:alert(1)", "https://",
            "https://user:password@counseling.example", "https://@counseling.example",
            "https://counseling.example/login", "https://counseling.example/api/",
            "https://counseling.example/../", "https://counseling.example/%2f",
            "https://counseling.example?", "https://counseling.example/#",
            "https://counseling.example:0", "https://counseling.example:65536",
            "https://counseling.example:abc", "https://counseling.example\\evil",
            "https://counseling.example/a b"})
    void rejectsMalformedOrNonRootAddresses(String input) {
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://counseling.example", "http://192.168.1.5:8080",
            "http://localhost.counseling.example", "http://127.0.0.1.counseling.example",
            "http://127.256.0.1", "http://127.00.0.1", "http://2130706433",
            "http://[::ffff:127.0.0.1]", "http://[2001:db8::1]", "http://localhost."})
    void refusesRemoteHttpAndAmbiguousLoopbackSpellings(String input) {
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(input));
    }

    @Test
    void nullAddressIsRejectedWithValidationError() {
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse(null));
    }

    @Test
    void navigationUsesSchemeHostAndEffectivePort() {
        ServerAddress server = ServerAddress.parse("https://counseling.example");
        assertTrue(server.allowsNavigation("https://COUNSELING.EXAMPLE:443/chat?thread=1#message"));
        assertTrue(server.allowsNavigation("https://counseling.example/api/auth/session"));
        assertFalse(server.allowsNavigation("http://counseling.example/login"));
        assertFalse(server.allowsNavigation("https://counseling.example:8443/login"));
        assertFalse(server.allowsNavigation("https://other.example/login"));
        assertFalse(server.allowsNavigation("https://counseling.example.evil.example/login"));
        assertFalse(server.allowsNavigation("https://user@counseling.example/login"));
        assertFalse(server.allowsNavigation("//counseling.example/login"));
        assertFalse(server.allowsNavigation("file:///C:/login.html"));
        assertFalse(server.allowsNavigation("data:text/html,hello"));
        assertFalse(server.allowsNavigation("about:blank"));
        assertFalse(server.allowsNavigation("/login"));
        assertFalse(server.allowsNavigation(null));
    }

    @Test
    void customPortAndIpv6OriginAreKept() {
        ServerAddress server = ServerAddress.parse("https://[::1]:8443");
        assertEquals("https://[::1]:8443/login", server.login().toString());
        assertTrue(server.allowsNavigation("https://[::1]:8443/chat"));
        assertFalse(server.allowsNavigation("https://[::1]/chat"));
    }
}
