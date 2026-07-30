package com.dk.dkaiagent.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 当前登录主体访问入口（冻结合约 AUTH-v1）。
 * principal 为 {@link SecurityConfig.PsychUserPrincipal}；SecurityContextHolder 是 ThreadLocal 语义，
 * 所有控制器（含 SSE 端点）必须在请求线程内取出 ownerId 再传入下游，不得跨 Reactor 线程边界读取。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 取当前登录用户 id；未认证抛 401。过滤器链已保证受保护路由必已认证，此处为纵深防御。 */
    public static long requireUserId() {
        return optionalUserId().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
    }

    public static Optional<Long> optionalUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SecurityConfig.PsychUserPrincipal principal) {
            return Optional.of(principal.id());
        }
        return Optional.empty();
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
