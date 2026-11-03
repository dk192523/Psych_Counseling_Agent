package com.dk.dkaiagent.account;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内登录限流（冻结合约 AUTH-v1）：同一 username 15 分钟内失败 5 次锁定 15 分钟，
 * 登录成功清零。多副本部署下限流是每进程独立的软防护，这是本次有意的权衡。
 * 无在途请求的过期窗口定期清理；有在途比对时保留计数，避免旧请求结算释放新窗口名额。
 *
 * 并发准入：{@link #tryBeginCheck(String)} 在单个 compute 内完成「锁定/预算判定 + 在途名额预扣」，
 * 使每窗口每进程放行的真实 BCrypt 比对严格 ≤ LOCK_THRESHOLD——堵住旧版「先查 isLocked 后记 recordFailure」
 * 结构下并发爆发波次全部在失败计数落地前通过检查的竞态（100 并发即 100 次真实猜测）。
 * 合约语义保持「5 次真实失败才锁」：在途名额不计失败，经 recordFailure（记失败并释放）/
 * recordSuccess（失败清零，仅归还自身名额）/ releaseInFlight（仅释放）配对归还。
 */
@Service
public class LoginAttemptService {

    public static final int LOCK_THRESHOLD = 5;
    public static final long WINDOW_MINUTES = 15;
    public static final long LOCK_MINUTES = 15;

    private static final long WINDOW_MILLIS = Duration.ofMinutes(WINDOW_MINUTES).toMillis();
    private static final long LOCK_MILLIS = Duration.ofMinutes(LOCK_MINUTES).toMillis();

    public static final int MAX_ACCOUNT_KEYS = 10_000;
    private final ConcurrentMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxKeys;
    private long nextCleanup;

    public LoginAttemptService() { this(Clock.systemUTC(), MAX_ACCOUNT_KEYS); }

    LoginAttemptService(Clock clock, int maxKeys) {
        if (maxKeys < 1) throw new IllegalArgumentException("Account key cap must be positive");
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    private static boolean validKey(String username) {
        return username != null && !username.isBlank() && username.length() <= AuthValidation.USERNAME_MAX_LENGTH;
    }

    private boolean canAllocate(String username) {
        expireIdleWindows();
        return attempts.containsKey(username) || attempts.size() < maxKeys;
    }

    synchronized int accountCount() { return attempts.size(); }

    private record AttemptWindow(int failures, int inFlight, long windowStartMillis, long lockedUntilMillis) {
    }

    /**
     * 原子准入：单次 compute 内判定并预扣一个校验名额。
     * 锁定中，或窗口内 failures + inFlight 达阈值（预算耗尽，补足锁定）→ 拒绝；否则 inFlight+1 放行。
     * 调用方获得 true 后必须恰好配对一次 recordFailure / recordSuccess / releaseInFlight 归还名额。
     */
    public synchronized boolean tryBeginCheck(String username) {
        if (!validKey(username)) {
            return false;
        }
        if (!canAllocate(username)) return false;
        boolean[] admitted = {false};
        attempts.compute(username, (key, existing) -> {
            long now = clock.millis();
            if (existing == null) {
                admitted[0] = true;
                return new AttemptWindow(0, 1, now, 0);
            }
            if (existing.lockedUntilMillis() > now) {
                return existing;
            }
            boolean expired = existing.inFlight() == 0 &&
                    (existing.lockedUntilMillis() > 0 || now - existing.windowStartMillis() > WINDOW_MILLIS);
            int failures = expired ? 0 : existing.failures();
            int inFlight = expired ? 0 : existing.inFlight();
            long windowStart = expired ? now : existing.windowStartMillis();
            if (failures + inFlight >= LOCK_THRESHOLD) {
                // 预算耗尽：补足锁定，本窗口不再放行新比对。
                return new AttemptWindow(failures, inFlight, windowStart, now + LOCK_MILLIS);
            }
            admitted[0] = true;
            return new AttemptWindow(failures, inFlight + 1, windowStart, 0);
        });
        return admitted[0];
    }

    /** 记一次真实失败并释放在途名额：累计达阈值即置锁。 */
    public synchronized void recordFailure(String username) {
        if (!validKey(username)) {
            return;
        }
        // 已准入请求必须正常结算；只有新键分配需要检查容量并回收空闲窗口。
        if (!attempts.containsKey(username) && !canAllocate(username)) return;
        attempts.compute(username, (key, existing) -> {
            long now = clock.millis();
            if (existing == null) {
                return new AttemptWindow(1, 0, now, 0);
            }
            boolean windowExpired = existing.inFlight() == 0 && (now - existing.windowStartMillis() > WINDOW_MILLIS
                    || (existing.lockedUntilMillis() > 0 && existing.lockedUntilMillis() <= now));
            int failures = windowExpired ? 1 : existing.failures() + 1;
            long windowStart = windowExpired ? now : existing.windowStartMillis();
            long lockedUntil = failures >= LOCK_THRESHOLD ? now + LOCK_MILLIS : existing.lockedUntilMillis();
            return new AttemptWindow(failures, Math.max(0, existing.inFlight() - 1), windowStart, lockedUntil);
        });
    }

    /** 归还在途名额但不计失败（DISABLED 早退、比对异常等路径）。 */
    public synchronized void releaseInFlight(String username) {
        if (!validKey(username)) {
            return;
        }
        attempts.computeIfPresent(username, (key, existing) -> new AttemptWindow(
                existing.failures(), Math.max(0, existing.inFlight() - 1),
                existing.windowStartMillis(), existing.lockedUntilMillis()));
    }

    public synchronized boolean isLocked(String username) {
        if (username == null) {
            return false;
        }
        AttemptWindow window = attempts.get(username);
        if (window == null) {
            return false;
        }
        long now = clock.millis();
        if (window.lockedUntilMillis() > now) {
            return true;
        }
        if (window.inFlight() == 0 && (window.lockedUntilMillis() > 0 || now - window.windowStartMillis() > WINDOW_MILLIS)) {
            attempts.remove(username, window);
        }
        return false;
    }

    public synchronized void recordSuccess(String username) {
        if (username != null) {
            // 登录成功可以清除失败记录，但不能释放其他尚在比对的请求。
            attempts.computeIfPresent(username, (key, existing) -> existing.inFlight() <= 1 ? null
                    : new AttemptWindow(0, existing.inFlight() - 1, clock.millis(), 0));
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public synchronized void expireIdleWindows() {
        long now = clock.millis();
        attempts.forEach((key, window) -> {
            if (window.inFlight() == 0 && window.lockedUntilMillis() <= now
                    && (window.lockedUntilMillis() > 0 || now - window.windowStartMillis() >= WINDOW_MILLIS)) attempts.remove(key, window);
        });
    }
}
