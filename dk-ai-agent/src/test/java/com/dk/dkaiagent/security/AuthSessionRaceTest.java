package com.dk.dkaiagent.security;

import com.dk.dkaiagent.account.*;
import com.dk.dkaiagent.controller.AuthController;
import com.dk.dkaiagent.security.dto.LoginRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthSessionRaceTest {
    UserRepository repo;
    UserAccountService users;
    ActiveSessionService sessions;
    AuthController controller;
    AtomicReference<PsychUser> current;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    @BeforeEach void setup() {
        repo = mock(UserRepository.class);
        var now = Instant.now();
        current = new AtomicReference<>(new PsychUser(7,"alice",encoder.encode("OldPassword123"),"USER","ACTIVE",now,now,null,null,null));
        when(repo.findById(7)).thenAnswer(call -> Optional.of(current.get()));
        when(repo.findByUsername("alice")).thenAnswer(call -> Optional.of(current.get()));
        when(repo.updatePasswordHash(eq(7L),anyString())).thenAnswer(call -> {
            var u=current.get(); current.set(new PsychUser(u.id(),u.username(),call.getArgument(1),u.role(),u.status(),now,now,null,null,null)); return 1;
        });
        sessions = spy(new ActiveSessionService(new SessionRegistryImpl()));
        users = spy(new UserAccountService(repo,new LoginAttemptService(),encoder,Optional.of(sessions)));
        controller = new AuthController(users,sessions,mock(RegisterThrottleService.class));
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void resetBeforeOrAfterRegistrationRejectsOldCredential(boolean afterRegistration) {
        if (afterRegistration) {
            doAnswer(call -> { call.callRealMethod(); users.adminResetPassword(1,7); return null; })
                    .when(sessions).registerLogin(eq(7L),any());
        } else {
            doAnswer(call -> { var result=call.callRealMethod(); users.adminResetPassword(1,7); return result; })
                    .when(users).authenticate("alice","OldPassword123");
        }
        var request = new MockHttpServletRequest();
        var error = assertThrows(ResponseStatusException.class, () -> controller.login(
                new LoginRequest("alice","OldPassword123"),request,new MockHttpServletResponse()));
        assertEquals(401,error.getStatusCode().value());
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(encoder.matches("OldPassword123",current.get().passwordHash()));
    }

    @Test void failedCredentialRecheckCannotLeaveAnAuthenticatedSession() {
        when(repo.findById(7)).thenThrow(new IllegalStateException("database unavailable"));
        var request=new MockHttpServletRequest();
        assertThrows(IllegalStateException.class, () -> controller.login(
                new LoginRequest("alice","OldPassword123"),request,new MockHttpServletResponse()));
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void rotationAfterCompletedLoginRevokesTheRegisteredSession() {
        var request=new MockHttpServletRequest();
        assertEquals(200,controller.login(new LoginRequest("alice","OldPassword123"),request,new MockHttpServletResponse()).getStatusCode().value());
        var session=(MockHttpSession)request.getSession(false);
        users.changeOwnPassword(7,"OldPassword123","NewPassword123");
        assertTrue(session.isInvalid());
    }
}
