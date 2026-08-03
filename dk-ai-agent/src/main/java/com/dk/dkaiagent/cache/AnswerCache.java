package com.dk.dkaiagent.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天回答缓存：同一会话、同一模式、同一输入且历史指纹未变时，在 TTL 内直接回放上一次的
 * 完整 SSE 事件流，整条检索/规划/生成管线一次都不执行，避免重复的 token 消耗。
 *
 * <p>键包含历史指纹（当前消息数）：中间一旦插入新轮次指纹即变化而 miss，不会拿旧答案回应新上下文；
 * 只有"完全相同的重复请求"才命中。只缓存以 done 结尾的完整成功回答，报错/中断/半截流不入库。
 * 命中时由控制器直接回放且不重复落库（重复请求视为同一轮）。进程内单实例，与本项目单实例假设一致。</p>
 */
@Component
public class AnswerCache {

    public record CachedEvent(String type, String content, String phase, String effectiveMode, boolean fallback) {
    }

    private record Entry(long expiresAt, List<CachedEvent> events) {
    }

    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public AnswerCache(
            @Value("${app.chat.answer-cache.enabled:true}") boolean enabled,
            @Value("${app.chat.answer-cache.ttl-seconds:600}") long ttlSeconds,
            @Value("${app.chat.answer-cache.max-entries:1000}") int maxEntries) {
        this.enabled = enabled;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
    }

    public boolean enabled() {
        return enabled;
    }

    public String key(String chatId, boolean deepThinking, String message, long historyFingerprint) {
        return chatId + "|" + (deepThinking ? "deep" : "fast") + "|" + historyFingerprint
                + "|" + sha256Hex(message == null ? "" : message);
    }

    public Optional<List<CachedEvent>> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.events());
    }

    /** 仅缓存以 done 结尾的完整成功回答；半截/失败流不入库。 */
    public void put(String key, List<CachedEvent> events) {
        if (!enabled || events == null || events.isEmpty()) {
            return;
        }
        if (!"done".equals(events.get(events.size() - 1).type())) {
            return;
        }
        if (store.size() >= maxEntries) {
            purgeExpired();
            if (store.size() >= maxEntries) {
                // 仍满则放弃本次缓存，绝不淘汰未过期项，避免引入误命中语义。
                return;
            }
        }
        store.put(key, new Entry(System.currentTimeMillis() + ttlMillis, List.copyOf(events)));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue().expiresAt()) {
                it.remove();
            }
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有 JRE 都可用；hashCode 仅作极端兜底。
            return Integer.toHexString(value.hashCode());
        }
    }
}
