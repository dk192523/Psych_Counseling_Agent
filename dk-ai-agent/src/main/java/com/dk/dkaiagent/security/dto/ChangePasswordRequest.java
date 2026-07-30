package com.dk.dkaiagent.security.dto;

/** POST /api/users/me/password 请求体。 */
public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
