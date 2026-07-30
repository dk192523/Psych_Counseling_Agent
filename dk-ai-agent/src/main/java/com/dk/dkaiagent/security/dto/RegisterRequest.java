package com.dk.dkaiagent.security.dto;

/** POST /api/auth/register 请求体。校验由 AuthValidation 统一完成。 */
public record RegisterRequest(String username, String password) {
}
