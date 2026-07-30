package com.dk.dkaiagent.security.dto;

/**
 * 统一 API 错误体（冻结合约 AUTH-v1）：{error,message}。
 * 控制器异常映射与 SecurityConfig 入口点/拒绝处理器共用此形状。
 */
public record ApiError(String error, String message) {
}
