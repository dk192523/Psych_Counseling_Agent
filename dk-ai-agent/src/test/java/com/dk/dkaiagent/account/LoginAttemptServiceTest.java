package com.dk.dkaiagent.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内登录限流（冻结合约 AUTH-v1）：同一 username 15 分钟内失败 5 次锁定 15 分钟，成功清零。
 * 窗口/锁定时长以真实时钟计算，此处只验证计数与清零语义，不做时间穿越。
 * 并发准入（tryBeginCheck 在途配额）：单窗口放行的真实比对严格 ≤ 阈值。
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void notLockedInitially() {
        assertFalse(service.isLocked("alice"));
    }

    @Test
    void notLockedBelowThreshold() {
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD - 1; i++) {
            service.recordFailure("alice");
        }
        assertFalse(service.isLocked("alice"));
    }

    @Test
    void lockedAtThreshold() {
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD; i++) {
            service.recordFailure("alice");
        }
        assertTrue(service.isLocked("alice"));
    }

    @Test
    void successResetsFailures() {
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD - 1; i++) {
            service.recordFailure("alice");
        }
        service.recordSuccess("alice");
        // 清零后需再次累计满阈值才会锁定。
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD - 1; i++) {
            service.recordFailure("alice");
        }
        assertFalse(service.isLocked("alice"));
    }

    @Test
    void failuresArePerUsername() {
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD; i++) {
            service.recordFailure("alice");
        }
        assertTrue(service.isLocked("alice"));
        assertFalse(service.isLocked("bob"));
    }

    @Test
    void nullAndBlankUsernamesAreIgnored() {
        service.recordFailure(null);
        service.recordFailure("   ");
        service.recordSuccess(null);
        assertFalse(service.isLocked(null));
        assertFalse(service.isLocked("   "));
    }

    // ---------------------------------------------------------------- 并发准入（在途配额）

    @Test
    void tryBeginCheckRejectsNullOrBlank() {
        assertFalse(service.tryBeginCheck(null));
        assertFalse(service.tryBeginCheck("   "));
    }

    @Test
    void sequentialBurstAdmitsAtMostThresholdComparisons() {
        // 在途配额预扣：即便 100 次连续准入（模拟失败计数落地前的并发波次），
        // 每窗口放行的真实 BCrypt 比对严格 ≤ 5（预算耗尽 → 补足锁定）。
        int admitted = 0;
        for (int i = 0; i < 100; i++) {
            if (service.tryBeginCheck("alice")) {
                admitted++;
            }
        }
        assertEquals(LoginAttemptService.LOCK_THRESHOLD, admitted);
        assertTrue(service.isLocked("alice"));
    }

    @Test
    void parallelBurstAdmitsAtMostThresholdComparisons() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (service.tryBeginCheck("alice")) {
                        admitted.incrementAndGet();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            // compute 内原子决策：真实并发下放行数同样严格等于阈值。
            assertEquals(LoginAttemptService.LOCK_THRESHOLD, admitted.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void releaseInFlightReturnsQuotaWithoutCountingFailure() {
        // DISABLED 早退 / 异常兜底路径释放在途名额：不计失败，可反复准入且始终不锁。
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD * 3; i++) {
            assertTrue(service.tryBeginCheck("alice"));
            service.releaseInFlight("alice");
        }
        assertFalse(service.isLocked("alice"));
    }

    @Test
    void beginThenFailureLocksAtThreshold() {
        // 标准失败路径：准入 → 记失败并释放名额，累计满阈值即锁。
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD; i++) {
            assertTrue(service.tryBeginCheck("alice"));
            service.recordFailure("alice");
        }
        assertFalse(service.tryBeginCheck("alice"));
        assertTrue(service.isLocked("alice"));
    }

    @Test
    void recordSuccessClearsInFlightQuota() {
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD; i++) {
            assertTrue(service.tryBeginCheck("alice"));
        }
        assertFalse(service.tryBeginCheck("alice"));
        // 成功登录整窗清零：在途名额一并释放，重新满阈值准入。
        service.recordSuccess("alice");
        for (int i = 0; i < LoginAttemptService.LOCK_THRESHOLD; i++) {
            assertTrue(service.tryBeginCheck("alice"));
        }
        assertFalse(service.isLocked("alice"));
    }
}
