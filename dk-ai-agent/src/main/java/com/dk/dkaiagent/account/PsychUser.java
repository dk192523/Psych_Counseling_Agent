package com.dk.dkaiagent.account;

import java.time.Instant;

/**
 * psych_user 领域行。可空字段：lastLoginAt / disabledAt / disabledReason。
 * passwordHash 绝不允许流入任何 API DTO，控制器层负责剥离（冻结合约 AUTH-v1）。
 */
public record PsychUser(
        long id,
        String username,
        String passwordHash,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        Instant disabledAt,
        String disabledReason
) {
}
