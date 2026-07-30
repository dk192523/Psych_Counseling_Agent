package com.dk.dkaiagent.security;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活跃会话登记与即时吊销测试（冻结合约 AUTH-v1）。停用账号时须只销毁该用户的会话，
 * 注册表 expireNow + 真实 HttpSession invalidate 双管齐下，其他用户的会话不受影响。
 */
@ExtendWith(MockitoExtension.class)
class ActiveSessionServiceTest {

    @Mock
    private SessionRegistry sessionRegistry;

    private ActiveSessionService service;

    @BeforeEach
    void setUp() {
        service = new ActiveSessionService(sessionRegistry);
    }

    @Test
    void registerLoginIndexesSessionByUserId() {
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("s1");
        when(sessionRegistry.getSessionInformation("s1")).thenReturn(null);

        service.registerLogin(5L, session);

        ArgumentCaptor<Object> principalCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sessionRegistry).registerNewSession(eq("s1"), principalCaptor.capture());
        Object principal = principalCaptor.getValue();
        assertInstanceOf(ActiveSessionService.SessionPrincipal.class, principal);
        assertEquals(5L, ((ActiveSessionService.SessionPrincipal) principal).userId());
    }

    @Test
    void registerLoginRemovesStaleIndexBeforeReRegistering() {
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("s1");
        SessionInformation existing = mock(SessionInformation.class);
        when(sessionRegistry.getSessionInformation("s1")).thenReturn(existing);

        service.registerLogin(5L, session);

        // 重复 sessionId 登记前先摘旧索引，避免 registerNewSession 抛重复异常。
        InOrder ordered = inOrder(sessionRegistry);
        ordered.verify(sessionRegistry).removeSessionInformation("s1");
        ordered.verify(sessionRegistry).registerNewSession(eq("s1"), any());
    }

    @Test
    void killSessionsExpiresAndInvalidatesOnlyTargetUserSessions() {
        HttpSession session5 = mock(HttpSession.class);
        HttpSession session6 = mock(HttpSession.class);
        when(session5.getId()).thenReturn("s5");
        when(session6.getId()).thenReturn("s6");
        when(sessionRegistry.getSessionInformation(anyString())).thenReturn(null);
        service.registerLogin(5L, session5);
        service.registerLogin(6L, session6);

        ActiveSessionService.SessionPrincipal principal5 = new ActiveSessionService.SessionPrincipal(5L);
        SessionInformation info5 = mock(SessionInformation.class);
        when(info5.getSessionId()).thenReturn("s5");
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(
                principal5, new ActiveSessionService.SessionPrincipal(6L)));
        when(sessionRegistry.getAllSessions(principal5, false)).thenReturn(List.of(info5));

        service.killSessions(5L);

        verify(info5).expireNow();
        verify(session5).invalidate();
        // 其他用户的会话不得被误杀。
        verify(session6, never()).invalidate();
    }

    @Test
    void killSessionsToleratesAlreadyInvalidatedSession() {
        HttpSession session5 = mock(HttpSession.class);
        when(session5.getId()).thenReturn("s5");
        when(sessionRegistry.getSessionInformation(anyString())).thenReturn(null);
        service.registerLogin(5L, session5);

        ActiveSessionService.SessionPrincipal principal5 = new ActiveSessionService.SessionPrincipal(5L);
        SessionInformation info5 = mock(SessionInformation.class);
        when(info5.getSessionId()).thenReturn("s5");
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal5));
        when(sessionRegistry.getAllSessions(principal5, false)).thenReturn(List.of(info5));
        org.mockito.Mockito.doThrow(new IllegalStateException("already invalidated"))
                .when(session5).invalidate();

        // 会话已自然过期/别处销毁时 invalidate 抛 IllegalStateException，killSessions 须吞掉。
        service.killSessions(5L);

        verify(info5).expireNow();
    }

    @Test
    void sessionDestroyedRemovesLiveReference() {
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("s1");
        when(sessionRegistry.getSessionInformation(anyString())).thenReturn(null);
        service.registerLogin(5L, session);

        jakarta.servlet.http.HttpSessionEvent event = mock(jakarta.servlet.http.HttpSessionEvent.class);
        when(event.getSession()).thenReturn(session);
        service.sessionDestroyed(event);

        // 活引用已清：killSessions 时不再有真实会话可 invalidate（注册表仍标记过期）。
        ActiveSessionService.SessionPrincipal principal5 = new ActiveSessionService.SessionPrincipal(5L);
        SessionInformation info5 = mock(SessionInformation.class);
        when(info5.getSessionId()).thenReturn("s1");
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal5));
        when(sessionRegistry.getAllSessions(principal5, false)).thenReturn(List.of(info5));
        service.killSessions(5L);

        verify(info5).expireNow();
        verify(session, never()).invalidate();
    }
}
