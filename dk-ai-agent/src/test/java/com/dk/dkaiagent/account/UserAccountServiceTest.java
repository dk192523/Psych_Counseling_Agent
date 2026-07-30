package com.dk.dkaiagent.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 账号域用例测试（冻结合约 AUTH-v1）：注册/登录/改密/管理端启停/重置/批量/统计，
 * 以及 SQL 注入探针。仓储与限流以 mock 隔离，密码编码器用真实 BCrypt 以验证落库哈希。
 */
@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private SessionKillPort sessionKillPort;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    private UserAccountService service;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(userRepository, loginAttemptService, passwordEncoder,
                Optional.of(sessionKillPort));
    }

    private static PsychUser user(long id, String username, String hash, String status) {
        return new PsychUser(id, username, hash, UserAccountService.ROLE_USER, status,
                NOW, NOW, null, null, null);
    }

    // ---------------------------------------------------------------- 注册

    @Test
    void registerRejectsInvalidUsername() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("ab", "password-123"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void registerRejectsShortPassword() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("alice", "short"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void registerRejectsTooLongPassword() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("alice", "a".repeat(73)));
        verifyNoInteractions(userRepository);
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userRepository.findByUsername("alice")).thenReturn(
                Optional.of(user(1L, "alice", "$2a$10$existing", UserAccountService.STATUS_ACTIVE)));

        assertThrows(UserAccountService.DuplicateUsernameException.class,
                () -> service.register("alice", "password-123"));
        verify(userRepository, never()).insertUser(anyString(), anyString(), anyString());
    }

    @Test
    void registerRejectsReservedAdminUsername() {
        // "admin"（大小写不敏感）为初始超管保留字：注册入口拒绝，封堵首启窗口抢注面。
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("admin", "password-123"));
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("Admin", "password-123"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void registerStoresBcryptHashNotPlaintext() {
        String rawPassword = "password-123";
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.insertUser(eq("alice"), anyString(), eq(UserAccountService.ROLE_USER)))
                .thenReturn(42L);
        when(userRepository.findById(42L)).thenReturn(
                Optional.of(user(42L, "alice", "$2a$10$whatever", UserAccountService.STATUS_ACTIVE)));

        PsychUser created = service.register("alice", rawPassword);

        assertEquals(42L, created.id());
        assertEquals("alice", created.username());
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).insertUser(eq("alice"), hashCaptor.capture(), eq(UserAccountService.ROLE_USER));
        String storedHash = hashCaptor.getValue();
        // 落库必须是 BCrypt 哈希，绝不能是原文。
        assertTrue(storedHash.startsWith("$2a$"), "expected bcrypt hash, got: " + storedHash);
        assertFalse(rawPassword.equals(storedHash));
        assertTrue(passwordEncoder.matches(rawPassword, storedHash));
    }

    @Test
    void registerMapsRaceDuplicateToConflictException() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.insertUser(eq("alice"), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThrows(UserAccountService.DuplicateUsernameException.class,
                () -> service.register("alice", "password-123"));
    }

    // ---------------------------------------------------------------- 登录

    @Test
    void authenticateBadCredentialsReturnsGenericErrorAndRecordsFailure() {
        when(loginAttemptService.tryBeginCheck("alice")).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(
                Optional.of(user(1L, "alice", passwordEncoder.encode("correct"), UserAccountService.STATUS_ACTIVE)));

        UserAccountService.AuthResult result = service.authenticate("alice", "wrong-password");

        assertFalse(result.ok());
        assertSame(UserAccountService.AuthFailure.BAD_CREDENTIALS, result.failure());
        // 泛化文案：不泄露账号是否存在。
        assertEquals(UserAccountService.GENERIC_AUTH_ERROR_MESSAGE, result.message());
        verify(loginAttemptService).recordFailure("alice");
    }

    @Test
    void authenticateUnknownUserReturnsSameGenericError() {
        when(loginAttemptService.tryBeginCheck("ghost")).thenReturn(true);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        UserAccountService.AuthResult result = service.authenticate("ghost", "whatever-123");

        assertFalse(result.ok());
        assertSame(UserAccountService.AuthFailure.BAD_CREDENTIALS, result.failure());
        assertEquals(UserAccountService.GENERIC_AUTH_ERROR_MESSAGE, result.message());
        verify(loginAttemptService).recordFailure("ghost");
    }

    @Test
    void authenticateDisabledAccountReturnsDisabledCode() {
        when(loginAttemptService.tryBeginCheck("alice")).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(
                Optional.of(user(1L, "alice", passwordEncoder.encode("correct"), UserAccountService.STATUS_DISABLED)));

        UserAccountService.AuthResult result = service.authenticate("alice", "correct");

        assertFalse(result.ok());
        assertSame(UserAccountService.AuthFailure.DISABLED, result.failure());
        assertEquals(UserAccountService.DISABLED_MESSAGE, result.message());
        // DISABLED 属于凭据正确后的状态判定，不计入失败计数，但须归还在途名额。
        verify(loginAttemptService, never()).recordFailure(anyString());
        verify(loginAttemptService).releaseInFlight("alice");
    }

    @Test
    void authenticateLockedShortCircuitsBeforeLookup() {
        when(loginAttemptService.tryBeginCheck("alice")).thenReturn(false);

        UserAccountService.AuthResult result = service.authenticate("alice", "anything-123");

        assertFalse(result.ok());
        assertSame(UserAccountService.AuthFailure.LOCKED, result.failure());
        assertEquals(UserAccountService.GENERIC_AUTH_ERROR_MESSAGE, result.message());
        // 准入拒绝直接短路：不查库、不比对、不再记失败。
        verify(userRepository, never()).findByUsername(anyString());
        verify(loginAttemptService, never()).recordFailure(anyString());
        verify(loginAttemptService, never()).releaseInFlight(anyString());
    }

    @Test
    void authenticateLocksAfterFiveFailuresThenAllowsAfterReset() {
        // 用真实限流器验证"失败 5 次锁定"的端到端语义。
        LoginAttemptService realLimiter = new LoginAttemptService();
        UserAccountService realService = new UserAccountService(userRepository, realLimiter, passwordEncoder,
                Optional.of(sessionKillPort));
        when(userRepository.findByUsername("alice")).thenReturn(
                Optional.of(user(1L, "alice", passwordEncoder.encode("correct"), UserAccountService.STATUS_ACTIVE)));

        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD; i++) {
            UserAccountService.AuthResult failure = realService.authenticate("alice", "wrong-password");
            assertSame(UserAccountService.AuthFailure.BAD_CREDENTIALS, failure.failure());
        }
        // 第 6 次即便密码正确也被锁定。
        UserAccountService.AuthResult locked = realService.authenticate("alice", "correct");
        assertSame(UserAccountService.AuthFailure.LOCKED, locked.failure());

        // 成功登录清零：解除锁定后（此处模拟锁定过期）成功登录会清空计数。
        realLimiter.recordSuccess("alice");
        assertFalse(realLimiter.isLocked("alice"));
    }

    @Test
    void authenticateSuccessUpdatesLastLoginAndResetsLimiter() {
        when(loginAttemptService.tryBeginCheck("alice")).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(
                Optional.of(user(7L, "alice", passwordEncoder.encode("correct"), UserAccountService.STATUS_ACTIVE)));

        UserAccountService.AuthResult result = service.authenticate("alice", "correct");

        assertTrue(result.ok());
        assertEquals(7L, result.user().id());
        verify(userRepository).updateLastLogin(7L);
        verify(loginAttemptService).recordSuccess("alice");
    }

    // ---------------------------------------------------------------- 改密 / 重置

    @Test
    void changeOwnPasswordUpdatesHashWhenOldPasswordMatches() {
        when(userRepository.findById(7L)).thenReturn(
                Optional.of(user(7L, "alice", passwordEncoder.encode("old-pass-123"), UserAccountService.STATUS_ACTIVE)));

        service.changeOwnPassword(7L, "old-pass-123", "new-pass-123");

        verify(userRepository).updatePasswordHash(eq(7L),
                argThat(hash -> hash.startsWith("$2a$") && passwordEncoder.matches("new-pass-123", hash)));
        // 凭据轮换即吊销全部旧会话：窃取会话的攻击者在密码被换后立即失效。
        verify(sessionKillPort).killSessions(7L);
    }

    @Test
    void changeOwnPasswordRejectsWrongOldPassword() {
        when(userRepository.findById(7L)).thenReturn(
                Optional.of(user(7L, "alice", passwordEncoder.encode("old-pass-123"), UserAccountService.STATUS_ACTIVE)));

        assertThrows(UserAccountService.BadOldPasswordException.class,
                () -> service.changeOwnPassword(7L, "not-the-old", "new-pass-123"));
        verify(userRepository, never()).updatePasswordHash(anyLong(), anyString());
    }

    @Test
    void changeOwnPasswordValidatesNewPassword() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.changeOwnPassword(7L, "old-pass-123", "short"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void adminResetPasswordReturnsTwelveCharAlphanumericAndStoresBcrypt() {
        when(userRepository.findById(9L)).thenReturn(
                Optional.of(user(9L, "bob", "$2a$10$old", UserAccountService.STATUS_ACTIVE)));

        String tempPassword = service.adminResetPassword(1L, 9L);

        assertEquals(12, tempPassword.length());
        assertTrue(tempPassword.matches("[A-Za-z0-9]{12}"), "temp password not alphanumeric: " + tempPassword);
        verify(userRepository).updatePasswordHash(eq(9L),
                argThat(hash -> hash.startsWith("$2a$") && passwordEncoder.matches(tempPassword, hash)));
        // 重置临时密码与停用同路径：立即踢掉目标用户全部会话。
        verify(sessionKillPort).killSessions(9L);
    }

    @Test
    void changeOwnPasswordWrongOldPasswordDoesNotKillSessions() {
        when(userRepository.findById(7L)).thenReturn(
                Optional.of(user(7L, "alice", passwordEncoder.encode("old-pass-123"), UserAccountService.STATUS_ACTIVE)));

        assertThrows(UserAccountService.BadOldPasswordException.class,
                () -> service.changeOwnPassword(7L, "not-the-old", "new-pass-123"));
        // 改密未成立：不得误踢会话。
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    @Test
    void adminResetPasswordUnknownUserThrows() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(UserAccountService.UserNotFoundException.class,
                () -> service.adminResetPassword(1L, 99L));
        verify(userRepository, never()).updatePasswordHash(anyLong(), anyString());
    }

    @Test
    void adminResetPasswordRejectsSelfOperation() {
        // 管理员不得经管理端点重置自身密码：绕过旧密码证明 + killSessions 会锁死合法管理员。
        assertThrows(UserAccountService.SelfOperationException.class,
                () -> service.adminResetPassword(7L, 7L));
        verify(userRepository, never()).updatePasswordHash(anyLong(), anyString());
        // self 检查先于一切副作用：不得误踢自身会话。
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    // ---------------------------------------------------------------- 启停 / 会话踢出

    @Test
    void adminDisableKillsSessions() {
        when(userRepository.findById(9L)).thenReturn(
                Optional.of(user(9L, "bob", "$2a$10$x", UserAccountService.STATUS_ACTIVE)));

        service.adminSetStatus(1L, 9L, true, "滥用");

        verify(userRepository).updateStatus(9L, UserAccountService.STATUS_DISABLED, "滥用");
        verify(sessionKillPort).killSessions(9L);
    }

    @Test
    void adminEnableClearsReasonAndDoesNotKillSessions() {
        when(userRepository.findById(9L)).thenReturn(
                Optional.of(user(9L, "bob", "$2a$10$x", UserAccountService.STATUS_DISABLED)));

        service.adminSetStatus(1L, 9L, false, "ignored-reason");

        verify(userRepository).updateStatus(9L, UserAccountService.STATUS_ACTIVE, null);
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    @Test
    void adminCannotDisableSelf() {
        assertThrows(UserAccountService.SelfOperationException.class,
                () -> service.adminSetStatus(1L, 1L, true, "self"));
        verify(userRepository, never()).updateStatus(anyLong(), anyString(), any());
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    @Test
    void adminDisableUnknownUserThrows() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(UserAccountService.UserNotFoundException.class,
                () -> service.adminSetStatus(1L, 99L, true, "x"));
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    // ---------------------------------------------------------------- 批量

    @Test
    void bulkDisablePutsSelfInFailedAndProcessesOthers() {
        when(userRepository.findById(2L)).thenReturn(
                Optional.of(user(2L, "u2", "$2a$10$x", UserAccountService.STATUS_ACTIVE)));
        when(userRepository.findById(3L)).thenReturn(
                Optional.of(user(3L, "u3", "$2a$10$x", UserAccountService.STATUS_ACTIVE)));

        UserAccountService.BulkResult result =
                service.bulkSetStatus(1L, List.of(1L, 2L, 3L), true, "批量停用");

        assertEquals(List.of(2L, 3L), result.succeeded());
        assertEquals(1, result.failed().size());
        assertEquals(1L, result.failed().get(0).id());
        assertEquals("SELF_OPERATION", result.failed().get(0).error());
        verify(userRepository).updateStatus(2L, UserAccountService.STATUS_DISABLED, "批量停用");
        verify(userRepository).updateStatus(3L, UserAccountService.STATUS_DISABLED, "批量停用");
        verify(userRepository, never()).updateStatus(eq(1L), anyString(), any());
        verify(sessionKillPort).killSessions(2L);
        verify(sessionKillPort).killSessions(3L);
        verify(sessionKillPort, never()).killSessions(1L);
    }

    @Test
    void bulkDisableMarksMissingUserAsNotFound() {
        when(userRepository.findById(2L)).thenReturn(
                Optional.of(user(2L, "u2", "$2a$10$x", UserAccountService.STATUS_ACTIVE)));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        UserAccountService.BulkResult result =
                service.bulkSetStatus(1L, List.of(2L, 99L), true, null);

        assertEquals(List.of(2L), result.succeeded());
        assertEquals(1, result.failed().size());
        assertEquals(99L, result.failed().get(0).id());
        assertEquals("NOT_FOUND", result.failed().get(0).error());
    }

    @Test
    void bulkEnableDoesNotKillSessionsAndClearsReason() {
        when(userRepository.findById(2L)).thenReturn(
                Optional.of(user(2L, "u2", "$2a$10$x", UserAccountService.STATUS_DISABLED)));

        UserAccountService.BulkResult result =
                service.bulkSetStatus(1L, List.of(2L), false, null);

        assertEquals(List.of(2L), result.succeeded());
        verify(userRepository).updateStatus(2L, UserAccountService.STATUS_ACTIVE, null);
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    @Test
    void bulkWithNullIdMarksInvalid() {
        when(userRepository.findById(2L)).thenReturn(
                Optional.of(user(2L, "u2", "$2a$10$x", UserAccountService.STATUS_ACTIVE)));

        // List.of 不允许 null 元素，此处用 Arrays.asList 构造含 null 的列表。
        UserAccountService.BulkResult result =
                service.bulkSetStatus(1L, java.util.Arrays.asList(2L, null), true, null);

        assertEquals(List.of(2L), result.succeeded());
        assertEquals(1, result.failed().size());
        assertEquals(-1L, result.failed().get(0).id());
        assertEquals("INVALID_ID", result.failed().get(0).error());
        // null id 不应触达仓储。
        verify(userRepository, never()).findById(-1L);
    }

    @Test
    void bulkEmptyListIsNoop() {
        UserAccountService.BulkResult result = service.bulkSetStatus(1L, List.of(), true, null);
        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.failed().isEmpty());
        verifyNoInteractions(userRepository);
    }

    @Test
    void bulkRejectsMoreThanHundredUsers() {
        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 0; i < 101; i++) {
            tooMany.add(i + 100);
        }
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.bulkSetStatus(1L, tooMany, true, null));
        verifyNoInteractions(userRepository);
    }

    // ---------------------------------------------------------------- 统计 / 列表

    @Test
    void adminStatsAggregatesRepositoryCounts() {
        when(userRepository.countAll()).thenReturn(10L);
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(2L);
        when(userRepository.countByStatus(UserAccountService.STATUS_ACTIVE)).thenReturn(8L);
        when(userRepository.countByStatus(UserAccountService.STATUS_DISABLED)).thenReturn(2L);
        when(userRepository.countConversations()).thenReturn(30L);
        when(userRepository.countMessages()).thenReturn(300L);

        UserAccountService.AdminStats stats = service.adminStats();

        assertEquals(new UserAccountService.AdminStats(10L, 2L, 8L, 2L, 30L, 300L), stats);
    }

    @Test
    void listUsersClampsPagingBounds() {
        when(userRepository.listUsers("kw", "ACTIVE", 0, 100)).thenReturn(List.of());
        when(userRepository.countUsers("kw", "ACTIVE")).thenReturn(0L);

        // 负页码收敛到 0；超大 size 收敛到 100。
        UserAccountService.UserPage page = service.listUsers("kw", "ACTIVE", -5, 500);

        assertEquals(0, page.page());
        assertEquals(100, page.size());
        verify(userRepository).listUsers("kw", "ACTIVE", 0, 100);
    }

    @Test
    void statusOfReturnsCurrentStatusAndFailsClosedWhenMissing() {
        when(userRepository.findById(7L)).thenReturn(
                Optional.of(user(7L, "alice", "$2a$10$x", UserAccountService.STATUS_DISABLED)));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // 登录会话登记后复核用：返回当前状态；行不存在收敛为 DISABLED（fail-closed）。
        assertEquals(UserAccountService.STATUS_DISABLED, service.statusOf(7L));
        assertEquals(UserAccountService.STATUS_DISABLED, service.statusOf(99L));
    }

    @Test
    void generateRandomPasswordUsesAlphabetAndRequestedLength() {
        String password = service.generateRandomPassword(12);
        assertEquals(12, password.length());
        assertTrue(password.matches("[A-Za-z0-9]{12}"));
    }

    // ---------------------------------------------------------------- SQL 注入探针

    @Test
    void registerRejectsInjectionUsernameBeforeTouchingRepository() {
        // 用户名正则拒绝引号/空格/分号：注入载荷在入口即被拦截，绝不进入 SQL。
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("admin' OR '1'='1", "password-123"));
        assertThrows(AuthValidation.ValidationException.class,
                () -> service.register("x'; DROP TABLE psych_user;--", "password-123"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void loginWithInjectionUsernameIsTreatedAsUnknownNotSqlError() {
        // 登录路径不做用户名校验（避免枚举），但仓储层参数化查询使注入载荷仅作字面值比对：
        // 查无此人 → 泛化 BAD_CREDENTIALS，无 SQL 异常泄露。
        when(loginAttemptService.tryBeginCheck("admin' OR '1'='1")).thenReturn(true);
        when(userRepository.findByUsername("admin' OR '1'='1")).thenReturn(Optional.empty());

        UserAccountService.AuthResult result = service.authenticate("admin' OR '1'='1", "password-123");

        assertFalse(result.ok());
        assertSame(UserAccountService.AuthFailure.BAD_CREDENTIALS, result.failure());
        assertEquals(UserAccountService.GENERIC_AUTH_ERROR_MESSAGE, result.message());
        // 仓储以完整注入串作为单一参数被调用（参数化），而非拼接。
        verify(userRepository).findByUsername("admin' OR '1'='1");
        verifyNoMoreInteractions(userRepository);
    }
}
