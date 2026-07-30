package com.dk.dkaiagent.account;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 账号域静态校验工具（冻结合约 AUTH-v1）。
 * 校验失败统一抛 {@link ValidationException}，控制器层映射为 400 {error:"VALIDATION",message}。
 */
public final class AuthValidation {

    public static final int USERNAME_MIN_LENGTH = 3;
    public static final int USERNAME_MAX_LENGTH = 32;
    public static final int PASSWORD_MIN_LENGTH = 8;
    /** BCrypt 密钥上限为 72 字节（UTF-8），超出 BCrypt 会静默截断，必须在入口显式拒绝。 */
    public static final int PASSWORD_MAX_BYTES = 72;

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{3,32}$");

    private AuthValidation() {
    }

    public static String normalizeUsername(String rawUsername) {
        return rawUsername == null ? "" : rawUsername.trim();
    }

    public static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new ValidationException("用户名须为 3-32 位字母、数字、下划线或中文");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new ValidationException("密码长度至少 " + PASSWORD_MIN_LENGTH + " 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new ValidationException("密码过长：UTF-8 编码后不得超过 " + PASSWORD_MAX_BYTES + " 字节");
        }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
