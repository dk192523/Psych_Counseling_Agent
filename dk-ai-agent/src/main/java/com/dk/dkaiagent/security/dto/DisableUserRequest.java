package com.dk.dkaiagent.security.dto;

/** POST /api/admin/users/{id}/disable 请求体；reason 可省略（整个 body 亦可省略）。 */
public record DisableUserRequest(String reason) {
}
