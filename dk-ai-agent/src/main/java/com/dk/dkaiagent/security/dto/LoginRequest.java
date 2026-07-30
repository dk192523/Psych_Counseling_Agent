package com.dk.dkaiagent.security.dto;

/** POST /api/auth/login 请求体。 */
public record LoginRequest(String username, String password) {
}
