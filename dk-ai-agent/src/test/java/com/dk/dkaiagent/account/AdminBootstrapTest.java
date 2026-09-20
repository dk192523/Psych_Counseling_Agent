package com.dk.dkaiagent.account;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 初始超管引导测试（冻结合约 AUTH-v1）：无 ADMIN 时创建 admin（口令落 BCrypt），
 * 已有 ADMIN 时跳过；唯一约束冲突须回查冲突行裁决——仅 ADMIN 占用按"已引导"处理并收养孤儿，
 * 非管理员占用（首启窗口抢注）记 ERROR 返回且绝不收养；env 配置口令同样走 8 位/72 字节校验。
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAccountService userAccountService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    private static PsychUser admin(long id, String hash) {
        return new PsychUser(id, "admin", hash, UserAccountService.ROLE_ADMIN,
                UserAccountService.STATUS_ACTIVE, NOW, NOW, null, null, null);
    }

    private static PsychUser nonAdminOccupant(long id) {
        return new PsychUser(id, "admin", "$2a$10$attacker", UserAccountService.ROLE_USER,
                UserAccountService.STATUS_ACTIVE, NOW, NOW, null, null, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "        "})
    void rejectsMissingConfiguredPasswordBeforeCreatingAdmin(String configuredPassword) {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        AdminBootstrap bootstrap =
                new AdminBootstrap(userRepository, userAccountService, passwordEncoder, configuredPassword);

        IllegalStateException error = assertThrows(IllegalStateException.class, bootstrap::ensureInitialAdmin);
        assertEquals("尚无管理员，首次启动必须配置 ADMIN_INITIAL_PASSWORD；未创建初始超管", error.getMessage());
        verify(userRepository, never()).insertUser(anyString(), anyString(), anyString());
        verify(userRepository, never()).adoptOrphanConversations(anyLong());
        verifyNoInteractions(userAccountService);
    }

    @Test
    void skipsCreationWhenAdminAlreadyExists() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(1L);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin(1L, "$2a$10$x")));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "");
        bootstrap.ensureInitialAdmin();

        verify(userRepository, never()).insertUser(anyString(), anyString(), anyString());
        // 即便跳过创建，遗留会话归属仍需执行（幂等）。
        verify(userRepository).adoptOrphanConversations(1L);
    }

    @Test
    void usesConfiguredInitialPasswordWhenPresent() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        when(userRepository.insertUser(eq("admin"), anyString(), eq(UserAccountService.ROLE_ADMIN)))
                .thenReturn(1L);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin(1L, "$2a$10$x")));

        AdminBootstrap bootstrap =
                new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "ConfiguredPass1");
        Logger logger = (Logger) LoggerFactory.getLogger(AdminBootstrap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            bootstrap.ensureInitialAdmin();

            ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
            verify(userRepository).insertUser(eq("admin"), hashCaptor.capture(), eq(UserAccountService.ROLE_ADMIN));
            String storedHash = hashCaptor.getValue();
            assertTrue(storedHash.startsWith("$2a$"));
            assertTrue(passwordEncoder.matches("ConfiguredPass1", storedHash));
            verify(userRepository).adoptOrphanConversations(1L);
            verifyNoInteractions(userAccountService);
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                    .equals("初始超管已创建，用户名 admin，初始口令来源 ADMIN_INITIAL_PASSWORD")));
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("ConfiguredPass1"));
                assertFalse(event.getFormattedMessage().contains(storedHash));
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void swallowsConcurrentCreationRaceAndStillAdoptsOrphans() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        when(userRepository.insertUser(eq("admin"), anyString(), eq(UserAccountService.ROLE_ADMIN)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        // 冲突行确为 ADMIN（另一实例已引导）：按"已引导"处理并收养孤儿。
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin(1L, "$2a$10$x")));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "ConfiguredPass1");

        assertDoesNotThrow(bootstrap::ensureInitialAdmin);
        verify(userRepository).adoptOrphanConversations(1L);
    }

    @Test
    void raceCollisionWithNonAdminOccupantDoesNotAdoptOrphans() {
        // 首启窗口抢注场景：攻击者先以 ROLE_USER 注册了 "admin"。唯一约束冲突后回查冲突行，
        // 发现非 ADMIN 占用 → 记 ERROR 返回，绝不把无主历史咨询会话归属到攻击者账号。
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        when(userRepository.insertUser(eq("admin"), anyString(), eq(UserAccountService.ROLE_ADMIN)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(nonAdminOccupant(2L)));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "ConfiguredPass1");

        assertDoesNotThrow(bootstrap::ensureInitialAdmin);
        verify(userRepository, never()).adoptOrphanConversations(anyLong());
    }

    @Test
    void adoptSkippedWhenAdminUsernameOccupiedByNonAdminEvenIfSomeAdminExists() {
        // 其他 ADMIN 账号存在（countByRole>0）但 "admin" 用户名被普通用户占用：
        // 归属断言阻止把孤儿会话写到非 ADMIN 行。
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(1L);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(nonAdminOccupant(2L)));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "");
        bootstrap.ensureInitialAdmin();

        verify(userRepository, never()).insertUser(anyString(), anyString(), anyString());
        verify(userRepository, never()).adoptOrphanConversations(anyLong());
    }

    @Test
    void rejectsShortConfiguredPasswordBeforeInsert() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);

        // 7 位 < 8 位下限：启动期 fail-closed，不落任何弱口令超管。
        AdminBootstrap bootstrap =
                new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "admin12");

        assertThrows(AuthValidation.ValidationException.class, bootstrap::ensureInitialAdmin);
        verify(userRepository, never()).insertUser(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsOverlongConfiguredPasswordBeforeInsert() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);

        // 73 ASCII 字节（>72）与 30 个中文（UTF-8 90 字节）两种越界形态均须拒绝，
        // 避免 BCrypt 静默截断导致有效口令与运维配置不符。
        AdminBootstrap asciiBootstrap =
                new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "a".repeat(73));
        assertThrows(AuthValidation.ValidationException.class, asciiBootstrap::ensureInitialAdmin);

        AdminBootstrap multibyteBootstrap =
                new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "密".repeat(30));
        assertThrows(AuthValidation.ValidationException.class, multibyteBootstrap::ensureInitialAdmin);

        verify(userRepository, never()).insertUser(anyString(), anyString(), anyString());
    }
}
