package com.dk.dkaiagent.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户名/密码入口校验（冻结合约 AUTH-v1）。
 * 用户名正则 ^[A-Za-z0-9_一-龥]{3,32}$；密码 8..72 字节（BCrypt 上限）。
 */
class AuthValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "user_123", "A_b9", "中文用户", "管理员admin"})
    void acceptsValidUsernames(String username) {
        AuthValidation.validateUsername(username);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "has-dash", "has.dot", "中文a!", "x' OR '1'='1"})
    void rejectsInvalidUsernames(String username) {
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validateUsername(username));
    }

    @Test
    void rejectsTooLongUsername() {
        String thirtyThree = "a".repeat(33);
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validateUsername(thirtyThree));
    }

    @Test
    void rejectsNullAndBlankUsername() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validateUsername(null));
        // normalizeUsername(null) → "" → 校验失败。
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validateUsername(AuthValidation.normalizeUsername(null)));
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validateUsername(AuthValidation.normalizeUsername("   ")));
    }

    @Test
    void normalizeUsernameTrimsWhitespace() {
        assertEquals("alice", AuthValidation.normalizeUsername("  alice  "));
    }

    @Test
    void acceptsPasswordAtLowerBound() {
        AuthValidation.validatePassword("12345678");
    }

    @Test
    void rejectsPasswordBelowLowerBound() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validatePassword("1234567"));
    }

    @Test
    void acceptsPasswordAtByteUpperBound() {
        // 72 个 ASCII 字符 = 72 字节，恰在上限。
        AuthValidation.validatePassword("a".repeat(72));
    }

    @Test
    void rejectsPasswordAboveByteUpperBound() {
        // 73 个 ASCII 字符 = 73 字节，超出 BCrypt 72 字节密钥上限。
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validatePassword("a".repeat(73)));
    }

    @Test
    void rejectsMultibytePasswordExceedingByteBound() {
        // 30 个汉字 = 90 字节：字符数满足 >=8 但 UTF-8 字节数超 72，仍须拒绝。
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validatePassword("密".repeat(30)));
    }

    @Test
    void rejectsNullPassword() {
        assertThrows(AuthValidation.ValidationException.class,
                () -> AuthValidation.validatePassword(null));
    }
}
