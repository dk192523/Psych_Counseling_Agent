package com.dk.dkaiagent.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天频率限制（A4 成本护栏）：每用户滑动窗口内限制发消息次数。
 * 之前没有任何业务层限流——一个失控的前端循环或脚本会无限消耗 LLM API。
 *
 * <p>实现为进程内滑动窗口（与 RegisterThrottleService 同一模式）：单副本部署下足够；
 * 多副本时限制变为"每副本 N 条"，可接受（放宽而非失效）。窗口修剪在访问时惰性执行，
 * Map 键数受活跃用户数约束——个人项目规模下无需后台清扫线程。</p>
 */
@Component
public class ChatRateLimitService {

    private final Map<Long, Deque<Long>> windowsByUser = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowMillis;

    public ChatRateLimitService(
            @Value("${app.chat-rate-limit.limit:12}") int limit,
            @Value("${app.chat-rate-limit.window-seconds:60}") long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("chat-rate-limit values must be positive");
        }
        this.limit = limit;
        this.windowMillis = windowSeconds * 1_000;
    }

    /**
     * @return true 表示放行（并记入窗口）；false 表示超限（本轮拒绝）
     */
    public boolean tryAcquire(long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> window = windowsByUser.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
