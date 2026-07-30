package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.account.AuthValidation;
import com.dk.dkaiagent.account.PsychUser;
import com.dk.dkaiagent.account.RegisterThrottleService;
import com.dk.dkaiagent.account.UserAccountService;
import com.dk.dkaiagent.security.ActiveSessionService;
import com.dk.dkaiagent.security.CurrentUser;
import com.dk.dkaiagent.security.SecurityConfig;
import com.dk.dkaiagent.security.dto.ApiError;
import com.dk.dkaiagent.security.dto.AuthUserDto;
import com.dk.dkaiagent.security.dto.LoginRequest;
import com.dk.dkaiagent.security.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证端点（冻结合约 AUTH-v1）。登录/注册为手工认证：凭据校验全部委托 UserAccountService
 * （内含限流、时序拉平、停用判定），控制器只负责建立会话与映射错误码。
 */
@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    private final UserAccountService userAccountService;
    private final ActiveSessionService activeSessionService;
    private final RegisterThrottleService registerThrottle;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserAccountService userAccountService,
                          ActiveSessionService activeSessionService,
                          RegisterThrottleService registerThrottle) {
        this.userAccountService = userAccountService;
        this.activeSessionService = activeSessionService;
        this.registerThrottle = registerThrottle;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request,
                                      HttpServletRequest servletRequest,
                                      HttpServletResponse servletResponse) {
        // 未认证注册限流：409/201 可被批量枚举账号存在性（并为首启抢注提供探测手段），
        // 双键窗口（来源 IP + 归一化用户名）超限统一泛化 429，不区分触发维度。
        String normalizedUsername = AuthValidation.normalizeUsername(request.username());
        String clientIp = resolveClientIp(servletRequest);
        if (registerThrottle.isRegisterBlocked(clientIp) || registerThrottle.isUsernameBlocked(normalizedUsername)) {
            log.warn("注册请求被限流：ip={}, username={}", clientIp, normalizedUsername);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ApiError("RATE_LIMITED", "请求过于频繁，请稍后再试"));
        }
        registerThrottle.recordRegister(clientIp, normalizedUsername);
        try {
            PsychUser user = userAccountService.register(request.username(), request.password());
            establishSession(user, servletRequest, servletResponse);
            log.info("注册成功：ip={}, userId={}, username={}", clientIp, user.id(), user.username());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new AuthUserDto(user.id(), user.username(), user.role()));
        } catch (UserAccountService.DuplicateUsernameException duplicate) {
            // 审计：同 IP 的 409 突增即枚举/占号行为信号，便于管理端从日志发现。
            log.warn("注册重名被拒：ip={}, username={}", clientIp, normalizedUsername);
            throw duplicate;
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   HttpServletRequest servletRequest,
                                   HttpServletResponse servletResponse) {
        UserAccountService.AuthResult result = userAccountService.authenticate(request.username(), request.password());
        if (!result.ok()) {
            // BAD_CREDENTIALS 与 LOCKED 共用泛化文案不枚举账号存在性；DISABLED 单独可识别供前端提示。
            String code = switch (result.failure()) {
                case BAD_CREDENTIALS -> "BAD_CREDENTIALS";
                case DISABLED -> "DISABLED";
                case LOCKED -> "LOCKED";
            };
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiError(code, result.message()));
        }
        PsychUser user = result.user();
        establishSession(user, servletRequest, servletResponse);
        return ResponseEntity.ok(new AuthUserDto(user.id(), user.username(), user.role()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        // 过滤链已保证本路由必已认证；requireUserId 为纵深防御。
        CurrentUser.requireUserId();
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /**
     * 建立认证会话：手工登录不经过 SessionManagementFilter，须显式 changeSessionId 防固定
     * （等价 Spring 默认策略）；SecurityContext 落库到会话属性，供后续请求的 SecurityContextHolderFilter 加载；
     * 最后把会话登记进 ActiveSessionService 以支持停用即时吊销。
     */
    private void establishSession(PsychUser user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        SecurityConfig.PsychUserPrincipal principal =
                new SecurityConfig.PsychUserPrincipal(user.id(), user.username(), user.role());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        activeSessionService.registerLogin(user.id(), session);
        // 竞态补偿：登记后复核账号状态，闭合"登录与停用并发"的空窗。
        // adminSetStatus 内 updateStatus 序在 killSessions 之前（同线程保证），故只有两种偏序：
        //  (1) 停用线程的枚举发生在本登记之前 → 本会话未被杀，但此处复核查到非 ACTIVE → 自毁；
        //  (2) 枚举发生在登记之后 → 注册表已含本会话，枚举自身可杀，此处复核读到 ACTIVE。
        // 两路互补无空隙，竞态存活的 DISABLED 会话不再可能。
        String freshStatus = userAccountService.statusOf(user.id());
        if (!UserAccountService.STATUS_ACTIVE.equals(freshStatus)) {
            SecurityContextHolder.clearContext();
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // 并发 killSessions 已销毁会话，忽略即安全。
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "DISABLED");
        }
    }

    /** 来源 IP：nginx 同源反代下取 X-Forwarded-For 首跳，直连时 remoteAddr。仅用于限流键。 */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String firstHop = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }
        return request.getRemoteAddr();
    }

    @ExceptionHandler(AuthValidation.ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(AuthValidation.ValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", exception.getMessage()));
    }

    @ExceptionHandler(UserAccountService.DuplicateUsernameException.class)
    public ResponseEntity<ApiError> handleDuplicateUsername(UserAccountService.DuplicateUsernameException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_USERNAME", "用户名已存在"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", "请求体须为合法 JSON"));
    }
}
