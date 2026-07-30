package com.dk.dkaiagent.account;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内登录限流（冻结合约 AUTH-v1）：同一 username 15 分钟内失败 5 次锁定 15 分钟，
 * 登录成功清零。多副本部署下限流是每进程独立的软防护，这是本次有意的权衡。
 * 锁定态与窗口过期态都随访问惰性清理，避免陈旧条目无限堆积。
 *
 * 并发准入：{@link #tryBeginCheck(String)} 在单个 compute 内完成「锁定/预算判定 + 在途名额预扣」，
 * 使每窗口每进程放行的真实 BCrypt 比对严格 ≤ LOCK_THRESHOLD——堵住旧版「先查 isLocked 后记 recordFailure」
 * 结构下并发爆发波次全部在失败计数落地前通过检查的竞态（100 并发即 100 次真实猜测）。
 * 合约语义保持「5 次真实失败才锁」：在途名额不计失败，经 recordFailure（记失败并释放）/
 * recordSuccess（整窗清零）/ releaseInFlight（仅释放，如 DISABLED 早退与异常兜底）配对归还。
 */
@Service
public class LoginAttemptService {

    public static final int LOCK_THRESHOLD = 5;
    public static final long WINDOW_MINUTES = 15;
    public static final long LOCK_MINUTES = 15;

    private static final long WINDOW_MILLIS = Duration.ofMinutes(WINDOW_MINUTES).toMillis();
    private static final long LOCK_MILLIS = Duration.ofMinutes(LOCK_MINUTES).toMillis();

    private final ConcurrentMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    private record AttemptWindow(int failures, int inFlight, long windowStartMillis, long lockedUntilMillis) {
    }

    /**
     * 原子准入：单次 compute 内判定并预扣一个校验名额。
     * 锁定中，或窗口内 failures + inFlight 达阈值（预算耗尽，补足锁定）→ 拒绝；否则 inFlight+1 放行。
     * 调用方获得 true 后必须恰好配对一次 recordFailure / recordSuccess / releaseInFlight 归还名额。
     */
    public boolean tryBeginCheck(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        boolean[] admitted = {false};
        attempts.compute(username, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                admitted[0] = true;
                return new AttemptWindow(0, 1, now, 0);
            }
            if (existing.lockedUntilMillis() > now) {
                return existing;
            }
            boolean expired = existing.lockedUntilMillis() > 0 || now - existing.windowStartMillis() > WINDOW_MILLIS;
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
    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        attempts.compute(username, (key, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null) {
                return new AttemptWindow(1, 0, now, 0);
            }
            boolean windowExpired = now - existing.windowStartMillis() > WINDOW_MILLIS
                    || (existing.lockedUntilMillis() > 0 && existing.lockedUntilMillis() <= now);
            int failures = windowExpired ? 1 : existing.failures() + 1;
            long windowStart = windowExpired ? now : existing.windowStartMillis();
            long lockedUntil = failures >= LOCK_THRESHOLD ? now + LOCK_MILLIS : 0;
            return new AttemptWindow(failures, 0, windowStart, lockedUntil);
        });
    }

    /** 归还在途名额但不计失败（DISABLED 早退、比对异常等路径）。 */
    public void releaseInFlight(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        attempts.computeIfPresent(username, (key, existing) -> new AttemptWindow(
                existing.failures(), Math.max(0, existing.inFlight() - 1),
                existing.windowStartMillis(), existing.lockedUntilMillis()));
    }

    public boolean isLocked(String username) {
        if (username == null) {
            return false;
        }
        AttemptWindow window = attempts.get(username);
        if (window == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (window.lockedUntilMillis() > now) {
            return true;
        }
        if (window.lockedUntilMillis() > 0 || now - window.windowStartMillis() > WINDOW_MILLIS) {
            attempts.remove(username, window);
        }
        return false;
    }

    public void recordSuccess(String username) {
        if (username != null) {
            // 整窗移除：失败计数与在途名额一并清零。
            attempts.remove(username);
        }
    }
}
