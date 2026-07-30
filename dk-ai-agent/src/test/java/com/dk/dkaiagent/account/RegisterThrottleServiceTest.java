package com.dk.dkaiagent.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册限流测试（AUTH-v1 加固）：双键固定窗口——按来源 IP 15 分钟 10 次、按归一化用户名 15 分钟 3 次。
 * 窗口时长以真实时钟计算，此处只验证计数与键独立性，不做时间穿越。
 */
class RegisterThrottleServiceTest {

    private RegisterThrottleService throttle;

    @BeforeEach
    void setUp() {
        throttle = new RegisterThrottleService();
    }

    @Test
    void ipBelowLimitIsNotBlocked() {
        for (int i = 0; i < RegisterThrottleService.IP_WINDOW_MAX - 1; i++) {
            throttle.recordRegister("10.0.0.1", "user" + i);
        }
        assertFalse(throttle.isRegisterBlocked("10.0.0.1"));
    }

    @Test
    void ipAtLimitIsBlocked() {
        for (int i = 0; i < RegisterThrottleService.IP_WINDOW_MAX; i++) {
            throttle.recordRegister("10.0.0.1", "user" + i);
        }
        assertTrue(throttle.isRegisterBlocked("10.0.0.1"));
        // 其他 IP 不受影响。
        assertFalse(throttle.isRegisterBlocked("10.0.0.2"));
    }

    @Test
    void usernameAtLimitIsBlocked() {
        for (int i = 0; i < RegisterThrottleService.USERNAME_WINDOW_MAX; i++) {
            throttle.recordRegister("10.0.0." + i, "alice");
        }
        // 同一高价值用户名被不同 IP 持续探测也会被挡。
        assertTrue(throttle.isUsernameBlocked("alice"));
        assertFalse(throttle.isUsernameBlocked("bob"));
    }

    @Test
    void ipAndUsernameKeysAreIndependent() {
        // 用户名维度达阈值不影响 IP 维度判定，反之亦然。
        for (int i = 0; i < RegisterThrottleService.USERNAME_WINDOW_MAX; i++) {
            throttle.recordRegister("10.0.0." + i, "alice");
        }
        assertTrue(throttle.isUsernameBlocked("alice"));
        assertFalse(throttle.isRegisterBlocked("10.0.0.0"));
    }

    @Test
    void nullAndBlankKeysAreIgnored() {
        throttle.recordRegister(null, "alice");
        throttle.recordRegister("  ", null);
        assertFalse(throttle.isRegisterBlocked(null));
        assertFalse(throttle.isRegisterBlocked("  "));
        assertFalse(throttle.isUsernameBlocked(null));
        assertFalse(throttle.isUsernameBlocked("  "));
    }
}
