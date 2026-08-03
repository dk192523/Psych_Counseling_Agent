package com.dk.dkaiagent.security;

import com.dk.dkaiagent.account.PsychUser;
import com.dk.dkaiagent.account.RegisterThrottleService;
import com.dk.dkaiagent.account.UserAccountService;
import com.dk.dkaiagent.account.UserRepository;
import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.cache.AnswerCache;
import com.dk.dkaiagent.agent.counseling.CounselingAgentExecutor;
import com.dk.dkaiagent.history.ConversationHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全过滤链端到端测试（冻结合约 AUTH-v1）。@WebMvcTest 只装载 Web 层，@Import 真实
 * SecurityConfig 以验证过滤链本身；业务依赖全部 mock。
 *
 * 注意：server.servlet.context-path=/api 在 MockMvc 中不生效，控制器与过滤链匹配的都是
 * servlet 相对路径（/health、/auth/login、/admin/**、/ai/**），对外即 /api 前缀下的同名端点。
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityFilterChainTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAccountService userAccountService;

    @MockitoBean
    private RegisterThrottleService registerThrottle;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ActiveSessionService activeSessionService;

    @MockitoBean
    private CounselingApp counselingApp;

    @MockitoBean
    private ConversationHistoryService conversationHistoryService;

    @MockitoBean
    private CounselingAgentExecutor counselingAgentExecutor;

    @MockitoBean
    private AnswerCache answerCache;

    private static PsychUser activeUser(long id, String username) {
        return new PsychUser(id, username, "$2a$10$hash", "USER", "ACTIVE", NOW, NOW, null, null, null);
    }

    // ---------------------------------------------------------------- permitAll 白名单

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorReadinessPathIsNotBlockedByAuthentication() throws Exception {
        // The MVC slice has no Actuator endpoint, so 404 is expected. The contract here is that
        // Security must not turn this healthcheck subpath into 401 before Actuator handles it.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginIsPublicAndReachesController() throws Exception {
        // 凭据错误返回控制器映射的 BAD_CREDENTIALS（而非入口点的 UNAUTHORIZED），
        // 证明白名单放行、请求确实抵达控制器。
        when(userAccountService.authenticate("alice", "wrong-pass-1")).thenReturn(
                new UserAccountService.AuthResult(false,
                        UserAccountService.AuthFailure.BAD_CREDENTIALS, null, "用户名或密码错误"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-pass-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("BAD_CREDENTIALS"));
    }

    @Test
    void loginDisabledAccountReturnsDisabledCode() throws Exception {
        when(userAccountService.authenticate("alice", "password-1")).thenReturn(
                new UserAccountService.AuthResult(false,
                        UserAccountService.AuthFailure.DISABLED, null, "该账号已被停用"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("DISABLED"));
    }

    @Test
    void loginSuccessReturnsAuthUserDto() throws Exception {
        when(userAccountService.authenticate("alice", "password-1")).thenReturn(
                new UserAccountService.AuthResult(true, null, activeUser(7L, "alice"), null));
        // 会话登记后状态复核：ACTIVE 放行。
        when(userAccountService.statusOf(7L)).thenReturn(UserAccountService.STATUS_ACTIVE);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void loginRaceDisableIsRejectedAfterSessionRegistration() throws Exception {
        // 登录与停用并发：凭据校验通过后账号被停用 → 会话登记后的状态复核兜住，401 拒绝。
        when(userAccountService.authenticate("alice", "password-1")).thenReturn(
                new UserAccountService.AuthResult(true, null, activeUser(7L, "alice"), null));
        when(userAccountService.statusOf(7L)).thenReturn(UserAccountService.STATUS_DISABLED);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-1\"}"))
                .andExpect(status().isUnauthorized());
        verify(activeSessionService).registerLogin(eq(7L), any());
    }

    @Test
    void registerIsPublicAndCreatesUser() throws Exception {
        when(userAccountService.register("alice", "password-1")).thenReturn(activeUser(7L, "alice"));
        when(userAccountService.statusOf(7L)).thenReturn(UserAccountService.STATUS_ACTIVE);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void registerValidationFailureMapsTo400() throws Exception {
        when(userAccountService.register(any(), any()))
                .thenThrow(new com.dk.dkaiagent.account.AuthValidation.ValidationException("密码长度至少 8 位"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION"));
    }

    @Test
    void registerDuplicateMapsTo409() throws Exception {
        when(userAccountService.register(any(), any()))
                .thenThrow(new UserAccountService.DuplicateUsernameException());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_USERNAME"));
    }

    @Test
    void registerReservedAdminUsernameMapsTo400() throws Exception {
        // 保留字拒绝在 UserAccountService 内抛 ValidationException，控制器统一映射 400 VALIDATION。
        when(userAccountService.register("admin", "password-1"))
                .thenThrow(new com.dk.dkaiagent.account.AuthValidation.ValidationException("用户名为系统保留字"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION"));
    }

    @Test
    void registerThrottledMapsTo429() throws Exception {
        // 注册限流超限：泛化 429，不触达注册用例。
        when(registerThrottle.isRegisterBlocked(anyString())).thenReturn(true);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
        verify(userAccountService, never()).register(any(), any());
    }

    // ---------------------------------------------------------------- 认证要求

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void aiRequiresAuthenticationAndBlocksDownstream() throws Exception {
        mockMvc.perform(get("/ai/counseling/chat/sync")
                        .param("message", "你好")
                        .param("chatId", "chat-id"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // 未认证请求被过滤链拦在控制器之前，绝不进入聊天下游。
        verify(counselingApp, never()).doChatWithRag(anyLong(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 角色授权

    @Test
    void adminEndpointsForbiddenForRegularUser() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("bob").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void adminStatsForbiddenForUnauthenticated() throws Exception {
        mockMvc.perform(get("/admin/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void adminStatsAllowedForAdmin() throws Exception {
        when(userAccountService.adminStats()).thenReturn(
                new UserAccountService.AdminStats(10L, 2L, 8L, 2L, 30L, 300L));

        mockMvc.perform(get("/admin/stats").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(10))
                .andExpect(jsonPath("$.adminCount").value(2))
                .andExpect(jsonPath("$.disabledUsers").value(2))
                .andExpect(jsonPath("$.totalConversations").value(30))
                .andExpect(jsonPath("$.totalMessages").value(300));
    }

    // ---------------------------------------------------------------- DTO 泄露（HTTP 层）

    @Test
    void adminUserListSerializationOmitsPasswordHash() throws Exception {
        UserRepository.UserListRow row =
                new UserRepository.UserListRow(1L, "alice", "USER", "ACTIVE", NOW, NOW, null, null, 3L);
        when(userAccountService.listUsers(any(), any(), anyInt(), anyInt()))
                .thenReturn(new UserAccountService.UserPage(List.of(row), 1L, 0, 20));

        mockMvc.perform(get("/admin/users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("alice"))
                .andExpect(jsonPath("$.content[0].role").value("USER"))
                .andExpect(jsonPath("$.content[0].conversationCount").value(3))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
