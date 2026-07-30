package com.dk.dkaiagent.controller;

import com.dk.dkaiagent.account.AuthValidation;
import com.dk.dkaiagent.account.PsychUser;
import com.dk.dkaiagent.account.UserAccountService;
import com.dk.dkaiagent.account.UserRepository;
import com.dk.dkaiagent.security.CurrentUser;
import com.dk.dkaiagent.security.dto.ApiError;
import com.dk.dkaiagent.security.dto.ChangePasswordRequest;
import com.dk.dkaiagent.security.dto.MeDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 当前用户自身端点（冻结合约 AUTH-v1）：GET /api/auth/me 与 POST /api/users/me/password。
 * 两个路由不同前缀，故不设类级 @RequestMapping，路由按合约逐方法声明。
 */
@RestController
public class MeController {

    private final UserRepository userRepository;
    private final UserAccountService userAccountService;

    public MeController(UserRepository userRepository, UserAccountService userAccountService) {
        this.userRepository = userRepository;
        this.userAccountService = userAccountService;
    }

    @GetMapping("/auth/me")
    public MeDto me() {
        long userId = CurrentUser.requireUserId();
        // 正常路径下会话必随用户存在；若行已被删除（管理端操作竞态），按未认证处理。
        PsychUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
        return new MeDto(user.id(), user.username(), user.role(), user.status(),
                user.createdAt(), user.lastLoginAt());
    }

    @PostMapping("/users/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody ChangePasswordRequest request) {
        long userId = CurrentUser.requireUserId();
        userAccountService.changeOwnPassword(userId, request.oldPassword(), request.newPassword());
    }

    @ExceptionHandler(UserAccountService.BadOldPasswordException.class)
    public ResponseEntity<ApiError> handleBadOldPassword(UserAccountService.BadOldPasswordException exception) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_OLD_PASSWORD", "原密码错误"));
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
