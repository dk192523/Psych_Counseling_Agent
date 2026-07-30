package com.dk.dkaiagent.security.dto;

/** 注册/登录成功响应体 {id,username,role}（冻结合约 AUTH-v1）。 */
public record AuthUserDto(long id, String username, String role) {
}
