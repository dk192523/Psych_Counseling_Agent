package com.dk.dkaiagent.security.dto;

/** GET /api/admin/stats 响应体（冻结合约 AUTH-v1）。 */
public record StatsDto(
        long totalUsers,
        long adminCount,
        long activeUsers,
        long disabledUsers,
        long totalConversations,
        long totalMessages
) {
}
