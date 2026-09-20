package com.dk.dkaiagent.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Deletes only explicitly expired, idle conversations; never expires deletion tombstones. */
@Service
@DependsOn("chatTurnService")
public class ConversationRetentionService {
    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionService.class);
    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;
    private final TransactionTemplate transaction;

    public ConversationRetentionService(JdbcTemplate jdbc, RetentionProperties properties,
                                        PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        // A fresh transaction per candidate bounds locks and preserves already committed progress.
        // READ COMMITTED is also required by the existing bootstrap tombstone recheck.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void scheduledRetention() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            // Do not include JDBC exception messages or parameters in privacy audit logs.
            log.error("data_retention phase=batch_failed errorType={}", failure.getClass().getSimpleName());
        }
    }

    /** Internal entry point for a bounded local dry run; no HTTP deletion endpoint is exposed. */
    public BatchResult runOnce() {
        properties.validate();
        String runId = UUID.randomUUID().toString();
        boolean apply = properties.isApply();
        int days = properties.getConversationDays();
        int batchSize = properties.getBatchSize();
        String mode = apply ? "apply" : "dry-run";
        if (days == 0) {
            log.info("data_retention runId={} mode={} outcome=unconfigured candidates=0 deleted=0", runId, mode);
            return new BatchResult(runId, apply, false, 0, 0, 0, 0, List.of());
        }
        // Use database time and bound parameters, never concatenate configured SQL intervals.
        Timestamp cutoff = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP - CAST(? AS DOUBLE PRECISION) * INTERVAL '1 day'",
                Timestamp.class, days);
        if (cutoff == null) throw new IllegalStateException("Retention cutoff unavailable");
        List<String> candidates = jdbc.query("""
                SELECT c.id FROM psych_conversation c
                WHERE c.updated_at < ? AND c.owner_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM psych_chat_turn t
                    WHERE t.conversation_id = c.id AND t.status = 'RUNNING'
                  )
                ORDER BY c.updated_at ASC, c.id ASC
                LIMIT ?
                """, (rs, row) -> rs.getString("id"), cutoff, batchSize);
        List<String> sample = candidates.stream().limit(10).map(ConversationRetentionService::fingerprint).toList();
        log.info("data_retention runId={} mode={} phase=selected conversationDays={} cutoff={} batchSize={} candidates={} idSample={}",
                runId, mode, days, cutoff.toInstant(), batchSize, candidates.size(), sample);
        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        if (apply) {
            for (String chatId : candidates) {
                try {
                    boolean removed = Boolean.TRUE.equals(transaction.execute(status -> deleteIfStillExpired(chatId, cutoff)));
                    if (removed) {
                        deleted++;
                        // execute returns only after commit, so this is not a premature success log.
                        log.info("data_retention runId={} mode=apply outcome=deleted conversationFingerprint={}",
                                runId, fingerprint(chatId));
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException failure) {
                    failed++;
                    log.warn("data_retention runId={} mode=apply outcome=failed conversationFingerprint={} errorType={}",
                            runId, fingerprint(chatId), failure.getClass().getSimpleName());
                }
            }
        }
        log.info("data_retention runId={} mode={} phase=finished candidates={} deleted={} skipped={} failed={}",
                runId, mode, candidates.size(), deleted, skipped, failed);
        return new BatchResult(runId, apply, true, candidates.size(), deleted, skipped, failed, sample);
    }

    private boolean deleteIfStillExpired(String chatId, Timestamp cutoff) {
        // Same parent-row lock as ChatTurnService.begin/finish. A busy parent is retried next batch.
        var parents = jdbc.query("""
                SELECT owner_id, updated_at FROM psych_conversation
                WHERE id = ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Parent((Long) rs.getObject("owner_id"), rs.getTimestamp("updated_at")), chatId);
        if (parents.isEmpty()) return false;
        Parent parent = parents.getFirst();
        if (parent.ownerId() == null || !parent.updatedAt().before(cutoff)) return false;
        // Separate statement gets a fresh READ COMMITTED snapshot after acquiring the lock.
        Boolean running = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM psych_chat_turn WHERE conversation_id = ? AND status = 'RUNNING')
                """, Boolean.class, chatId);
        if (!Boolean.FALSE.equals(running)) return false;
        jdbc.update("""
                INSERT INTO psych_conversation_tombstone (conversation_id, owner_id)
                VALUES (?, ?) ON CONFLICT (conversation_id) DO NOTHING
                """, chatId, parent.ownerId());
        // FK cascades messages, digest and replay turns; tombstones intentionally have no parent FK.
        int removed = jdbc.update("DELETE FROM psych_conversation WHERE id = ?", chatId);
        if (removed != 1) throw new IllegalStateException("Retention deletion did not remove its locked parent");
        return true;
    }

    private static String fingerprint(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Parent(Long ownerId, Timestamp updatedAt) {}

    public record BatchResult(String runId, boolean apply, boolean configured, int candidates,
                              int deleted, int skipped, int failed, List<String> idSample) {}
}
