package com.dk.dkaiagent.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 当前登录主体访问入口测试（冻结合约 AUTH-v1）。principal 须为 PsychUserPrincipal；
 * 未认证时 requireUserId 抛 401，optionalUserId 空，isAdmin 假。
 */
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(long id, String username, String role) {
        SecurityConfig.PsychUserPrincipal principal = new SecurityConfig.PsychUserPrincipal(id, username, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void requireUserIdReturnsPrincipalId() {
        authenticate(7L, "alice", "USER");
        assertEquals(7L, CurrentUser.requireUserId());
    }

    @Test
    void optionalUserIdPresentWhenAuthenticated() {
        authenticate(7L, "alice", "USER");
        assertEquals(Optional.of(7L), CurrentUser.optionalUserId());
    }

    @Test
    void isAdminFalseForRegularUser() {
        authenticate(7L, "alice", "USER");
        assertFalse(CurrentUser.isAdmin());
    }

    @Test
    void isAdminTrueForAdminRole() {
        authenticate(1L, "admin", "ADMIN");
        assertTrue(CurrentUser.isAdmin());
        assertEquals(1L, CurrentUser.requireUserId());
    }

    @Test
    void requireUserIdThrowsUnauthorizedWhenAnonymous() {
        SecurityContextHolder.clearContext();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, CurrentUser::requireUserId);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        assertTrue(CurrentUser.optionalUserId().isEmpty());
        assertFalse(CurrentUser.isAdmin());
    }

    @Test
    void nonPsychPrincipalIsIgnored() {
        // 非 PsychUserPrincipal（如匿名字符串主体）不得被识别为已登录用户。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "anonymous", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertTrue(CurrentUser.optionalUserId().isEmpty());
        assertFalse(CurrentUser.isAdmin());
        assertThrows(ResponseStatusException.class, CurrentUser::requireUserId);
    }
}
