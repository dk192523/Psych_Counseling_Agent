package com.dk.dkaiagent.security;

import com.dk.dkaiagent.account.SessionKillPort;
import com.dk.dkaiagent.account.UserAccountService;
import com.dk.dkaiagent.account.UserRepository;
import com.dk.dkaiagent.controller.AdminUserController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端删除端点的自身保护与级联测试（冻结合约 AUTH-v1）。
 * 删除自身 → SELF_OPERATION；删除他人先级联删行再即时踢会话；目标不存在 → USER_NOT_FOUND。
 * 主体 id 由 CurrentUser 提供，单测中以静态桩固定。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    private static final long ADMIN_ID = 1L;

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SessionKillPort sessionKillPort;

    private AdminUserController controller;
    private MockedStatic<CurrentUser> currentUser;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(userAccountService, userRepository, Optional.of(sessionKillPort));
        currentUser = mockStatic(CurrentUser.class);
        currentUser.when(CurrentUser::requireUserId).thenReturn(ADMIN_ID);
    }

    @AfterEach
    void releaseMocks() {
        currentUser.close();
    }

    @Test
    void deleteSelfIsRejectedAsSelfOperation() {
        assertThrows(UserAccountService.SelfOperationException.class,
                () -> controller.deleteUser(ADMIN_ID));
        // 自身保护先于任何副作用：不得触达仓储与会话踢出。
        verify(userRepository, never()).deleteById(anyLong());
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    @Test
    void deleteOtherUserCascadesRowThenKillsSessions() {
        when(userRepository.deleteById(2L)).thenReturn(true);

        controller.deleteUser(2L);

        verify(userRepository).deleteById(2L);
        verify(sessionKillPort).killSessions(2L);
    }

    @Test
    void deleteUnknownUserThrowsNotFoundAndSkipsKill() {
        when(userRepository.deleteById(99L)).thenReturn(false);

        assertThrows(UserAccountService.UserNotFoundException.class,
                () -> controller.deleteUser(99L));
        verify(sessionKillPort, never()).killSessions(anyLong());
    }

    @Test
    void disableDelegatesActorIdToService() {
        controller.disableUser(2L, new com.dk.dkaiagent.security.dto.DisableUserRequest("滥用"));
        verify(userAccountService).adminSetStatus(ADMIN_ID, 2L, true, "滥用");
    }

    @Test
    void passwordResetDelegatesActorIdToService() {
        // actorId 必须传入服务层做 self 检查，堵住"管理员重置自身密码绕过旧密码证明"的端点。
        when(userAccountService.adminResetPassword(ADMIN_ID, 2L)).thenReturn("Ab3xYz9Kq2Lm");

        var response = controller.resetPassword(2L);

        org.junit.jupiter.api.Assertions.assertEquals("Ab3xYz9Kq2Lm", response.tempPassword());
        verify(userAccountService).adminResetPassword(ADMIN_ID, 2L);
    }
}
