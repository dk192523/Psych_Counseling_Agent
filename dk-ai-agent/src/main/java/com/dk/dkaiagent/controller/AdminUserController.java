package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.account.AuthValidation;
import com.dk.dkaiagent.account.SessionKillPort;
import com.dk.dkaiagent.account.UserAccountService;
import com.dk.dkaiagent.account.UserRepository;
import com.dk.dkaiagent.security.CurrentUser;
import com.dk.dkaiagent.security.dto.ApiError;
import com.dk.dkaiagent.security.dto.BulkStatusRequest;
import com.dk.dkaiagent.security.dto.DisableUserRequest;
import com.dk.dkaiagent.security.dto.StatsDto;
import com.dk.dkaiagent.security.dto.TempPasswordResponse;
import com.dk.dkaiagent.security.dto.UserDto;
import com.dk.dkaiagent.security.dto.UserPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 管理端用户治理端点（冻结合约 AUTH-v1）。ROLE_ADMIN 由过滤链统一强制；
 * 自身保护（SELF_OPERATION）与限流/批量上限在 UserAccountService 内强制，控制器只做入参整形与 DTO 映射。
 * DTO 严禁携带 password_hash（UserListRow 本身不含哈希，映射再收口一层）。
 */
@RestController
@RequestMapping("/admin")
public class AdminUserController {

    private final UserAccountService userAccountService;
    private final UserRepository userRepository;
    private final Optional<SessionKillPort> sessionKillPort;

    public AdminUserController(UserAccountService userAccountService,
                               UserRepository userRepository,
                               Optional<SessionKillPort> sessionKillPort) {
        this.userAccountService = userAccountService;
        this.userRepository = userRepository;
        this.sessionKillPort = sessionKillPort;
    }

    @GetMapping("/users")
    public UserPageResponse listUsers(@RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        UserAccountService.UserPage userPage = userAccountService.listUsers(keyword, status, page, size);
        List<UserDto> content = userPage.content().stream()
                .map(row -> new UserDto(
                        row.id(),
                        row.username(),
                        row.role(),
                        row.status(),
                        row.createdAt(),
                        row.lastLoginAt(),
                        row.disabledAt(),
                        row.disabledReason(),
                        row.conversationCount()))
                .toList();
        return new UserPageResponse(content, userPage.totalElements(), userPage.page(), userPage.size());
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<Void> disableUser(@PathVariable long id,
                                            @RequestBody(required = false) DisableUserRequest request) {
        userAccountService.adminSetStatus(CurrentUser.requireUserId(), id, true,
                request == null ? null : request.reason());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/users/{id}/enable")
    public ResponseEntity<Void> enableUser(@PathVariable long id) {
        userAccountService.adminSetStatus(CurrentUser.requireUserId(), id, false, null);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/users/bulk")
    public UserAccountService.BulkResult bulkSetStatus(@RequestBody BulkStatusRequest request) {
        boolean disable = switch (normalizeAction(request.action())) {
            case "DISABLE" -> true;
            case "ENABLE" -> false;
            default -> throw new AuthValidation.ValidationException("action 须为 DISABLE 或 ENABLE");
        };
        return userAccountService.bulkSetStatus(CurrentUser.requireUserId(), request.userIds(), disable,
                request.reason());
    }

    @PostMapping("/users/{id}/password-reset")
    public TempPasswordResponse resetPassword(@PathVariable long id) {
        // actorId 传入服务层做 self 检查（与启停同形）：SelfOperationException 由本类 @ExceptionHandler 转 400 SELF_OPERATION。
        long actorId = CurrentUser.requireUserId();
        return new TempPasswordResponse(userAccountService.adminResetPassword(actorId, id));
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable long id) {
        long actorId = CurrentUser.requireUserId();
        if (actorId == id) {
            throw new UserAccountService.SelfOperationException();
        }
        // 级联：deleteById 先删其会话行，消息/记忆经 psych_conversation 自身 FK 级联清理。
        if (!userRepository.deleteById(id)) {
            throw new UserAccountService.UserNotFoundException();
        }
        // 数据库行已清；其存活 HttpSession 在此即时销毁。
        sessionKillPort.ifPresent(killer -> killer.killSessions(id));
    }

    @GetMapping("/stats")
    public StatsDto stats() {
        UserAccountService.AdminStats stats = userAccountService.adminStats();
        return new StatsDto(
                stats.totalUsers(),
                stats.adminCount(),
                stats.activeUsers(),
                stats.disabledUsers(),
                stats.totalConversations(),
                stats.totalMessages());
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "";
        }
        return action.trim().toUpperCase(Locale.ROOT);
    }

    @ExceptionHandler(UserAccountService.SelfOperationException.class)
    public ResponseEntity<ApiError> handleSelfOperation(UserAccountService.SelfOperationException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("SELF_OPERATION", "不能对当前登录账号执行该操作"));
    }

    @ExceptionHandler(UserAccountService.UserNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UserAccountService.UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("USER_NOT_FOUND", "用户不存在"));
    }

    @ExceptionHandler(AuthValidation.ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(AuthValidation.ValidationException exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", "请求体须为合法 JSON"));
    }
}
