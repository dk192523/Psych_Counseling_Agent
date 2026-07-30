package com.dk.dkaiagent.security.dto;

import java.util.List;

/** GET /api/admin/users 分页响应 {content,totalElements,page,size}（冻结合约 AUTH-v1）。 */
public record UserPageResponse(List<UserDto> content, long totalElements, int page, int size) {
}
