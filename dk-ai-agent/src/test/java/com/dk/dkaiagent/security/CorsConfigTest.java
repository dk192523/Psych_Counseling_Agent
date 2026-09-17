package com.dk.dkaiagent.security;

import com.dk.dkaiagent.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    @Test void rejectsWildcardEvenWhenMixedWithTrustedOrigins() {
        var config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", new String[]{"https://trusted.example", "https://*.example"});
        assertThrows(IllegalArgumentException.class, () -> config.addCorsMappings(new CorsRegistry()));
    }
    @Test void acceptsExplicitOriginsAndEmptySameOriginConfiguration() {
        var config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", new String[]{"https://trusted.example"});
        assertDoesNotThrow(() -> config.addCorsMappings(new CorsRegistry()));
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", new String[]{""});
        assertDoesNotThrow(() -> config.addCorsMappings(new CorsRegistry()));
    }
}
