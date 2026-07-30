package com.dk.dkaiagent.account;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内注册限流（AUTH-v1 加固）：未认证的 /auth/register 以 409/201 响应区分用户名存在性
 * （重名提示有 UX 必要性），但也构成可脚本化的批量枚举与占号面。双键固定窗口限流使批量探测
 * 在经济上不可行，与 LoginAttemptService 的进程内软防护口径一致：
 *  - 按来源 IP（nginx 同源反代下取 X-Forwarded-For 首跳，否则 remoteAddr）：15 分钟内最多 10 次；
 *  - 按归一化 username：15 分钟内最多 3 次（防对 admin 等单一高价值用户名的持续精确探测）。
 * 过期条目随访问惰性清理。多副本部署下每进程独立计数（既有文档化权衡）；如需硬防护可在
 * nginx 层对 /api/auth/register 追加 limit_req 作为第一道闸。
 */
@Service
public class RegisterThrottleService {

    public static final int IP_WINDOW_MAX = 10;
    public static final int USERNAME_WINDOW_MAX = 3;
    public static final long WINDOW_MINUTES = 15;

    private static final long WINDOW_MILLIS = Duration.ofMinutes(WINDOW_MINUTES).toMillis();

    private final ConcurrentMap<String, HitWindow> ipHits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, HitWindow> usernameHits = new ConcurrentHashMap<>();

    private record HitWindow(int count, long windowStartMillis) {
    }

    public boolean isRegisterBlocked(String clientIp) {
        return isBlocked(ipHits, clientIp, IP_WINDOW_MAX);
    }

    public boolean isUsernameBlocked(String normalizedUsername) {
        return isBlocked(usernameHits, normalizedUsername, USERNAME_WINDOW_MAX);
    }

    /** 记一次注册尝试（无论后续成功与否都计数，避免以结果差异反馈探测）。 */
    public void recordRegister(String clientIp, String normalizedUsername) {
        record(ipHits, clientIp);
        record(usernameHits, normalizedUsername);
    }

    private static boolean isBlocked(ConcurrentMap<String, HitWindow> hits, String key, int limit) {
        if (key == null || key.isBlank()) {
            return false;
        }
        HitWindow window = hits.get(key);
        if (window == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - window.windowStartMillis() > WINDOW_MILLIS) {
            hits.remove(key, window);
            return false;
        }
        return window.count() >= limit;
    }

    private static void record(ConcurrentMap<String, HitWindow> hits, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        hits.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStartMillis() > WINDOW_MILLIS) {
                return new HitWindow(1, now);
            }
            return new HitWindow(existing.count() + 1, existing.windowStartMillis());
        });
    }
}
