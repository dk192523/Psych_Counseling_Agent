package com.dk.dkaiagent.security.dto;

/** POST /api/admin/users/{id}/password-reset 响应体；临时密码仅随本响应返回一次。 */
public record TempPasswordResponse(String tempPassword) {
}
