package com.dk.dkaiagent.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public compatibility probe; intentionally excludes infrastructure and secret details. */
@RestController
@RequestMapping("/system")
public class SystemHealthController {

    private final String serverVersion;
    private final String protocolVersion;

    public SystemHealthController(@Value("${app.system.server-version:dev}") String serverVersion,
                                  @Value("${app.system.protocol-version:1}") String protocolVersion) {
        this.serverVersion = serverVersion;
        this.protocolVersion = protocolVersion;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", serverVersion, protocolVersion);
    }

    public record HealthResponse(String status, String serverVersion, String protocolVersion) {
    }
}
