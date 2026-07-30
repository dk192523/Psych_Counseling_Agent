package com.dk.dkaiagent.security.dto;

import java.time.Instant;

/** GET /api/auth/me 响应体（冻结合约 AUTH-v1）。不含 passwordHash。 */
public record MeDto(
        long id,
        String username,
        String role,
        String status,
        Instant createdAt,
        Instant lastLoginAt
) {
}
