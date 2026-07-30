package com.dk.dkaiagent.security.dto;

import java.util.List;

/** POST /api/admin/users/bulk 请求体；userIds 上限 100（服务层强制），action ∈ {DISABLE, ENABLE}。 */
public record BulkStatusRequest(List<Long> userIds, String action, String reason) {
}
