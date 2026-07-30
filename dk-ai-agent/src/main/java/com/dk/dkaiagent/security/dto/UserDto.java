package com.dk.dkaiagent.security.dto;

import java.time.Instant;

/**
 * 管理端用户行 DTO（冻结合约 AUTH-v1）。
 * 严禁携带 password_hash：映射自 UserRepository.UserListRow（本身不含哈希），此处再作 API 层显式收口。
 */
public record UserDto(
        long id,
        String username,
        String role,
        String status,
        Instant createdAt,
        Instant lastLoginAt,
        Instant disabledAt,
        String disabledReason,
        long conversationCount
) {
}
