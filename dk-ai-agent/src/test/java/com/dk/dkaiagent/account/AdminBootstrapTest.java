package com.dk.dkaiagent.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void createsAdminWithBcryptHashWhenNoneExists() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        when(userAccountService.generateRandomPassword(12)).thenReturn("RandomPass12");
        when(userRepository.insertUser(eq("admin"), anyString(), eq(UserAccountService.ROLE_ADMIN)))
                .thenReturn(1L);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin(1L, "$2a$10$x")));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "");
        bootstrap.ensureInitialAdmin();

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).insertUser(eq("admin"), hashCaptor.capture(), eq(UserAccountService.ROLE_ADMIN));
        String storedHash = hashCaptor.getValue();
        assertTrue(storedHash.startsWith("$2a$"));
        assertTrue(passwordEncoder.matches("RandomPass12", storedHash));
        // 创建后把无主历史会话归属到超管。
        verify(userRepository).adoptOrphanConversations(1L);
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
        bootstrap.ensureInitialAdmin();

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository).insertUser(eq("admin"), hashCaptor.capture(), eq(UserAccountService.ROLE_ADMIN));
        assertTrue(passwordEncoder.matches("ConfiguredPass1", hashCaptor.getValue()));
        verify(userAccountService, never()).generateRandomPassword(12);
    }

    @Test
    void swallowsConcurrentCreationRaceAndStillAdoptsOrphans() {
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        when(userAccountService.generateRandomPassword(12)).thenReturn("RandomPass12");
        when(userRepository.insertUser(eq("admin"), anyString(), eq(UserAccountService.ROLE_ADMIN)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        // 冲突行确为 ADMIN（另一实例已引导）：按"已引导"处理并收养孤儿。
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin(1L, "$2a$10$x")));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "");

        assertDoesNotThrow(bootstrap::ensureInitialAdmin);
        verify(userRepository).adoptOrphanConversations(1L);
    }

    @Test
    void raceCollisionWithNonAdminOccupantDoesNotAdoptOrphans() {
        // 首启窗口抢注场景：攻击者先以 ROLE_USER 注册了 "admin"。唯一约束冲突后回查冲突行，
        // 发现非 ADMIN 占用 → 记 ERROR 返回，绝不把无主历史咨询会话归属到攻击者账号。
        when(userRepository.countByRole(UserAccountService.ROLE_ADMIN)).thenReturn(0L);
        when(userAccountService.generateRandomPassword(12)).thenReturn("RandomPass12");
        when(userRepository.insertUser(eq("admin"), anyString(), eq(UserAccountService.ROLE_ADMIN)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(nonAdminOccupant(2L)));

        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, userAccountService, passwordEncoder, "");

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
