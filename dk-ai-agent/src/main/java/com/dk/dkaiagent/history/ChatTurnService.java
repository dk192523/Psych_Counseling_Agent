package com.dk.dkaiagent.history;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Durable per-turn idempotency. The deployment remains single-replica (model memory/session state).
 * Parent-row locking serializes admission, completion and deletion without holding a DB connection
 * during model generation. Completed and interrupted replies are immutable and replayable.
 */
@Service
@DependsOn("userRepository")
public class ChatTurnService {
    private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);
    private final JdbcTemplate jdbc;
    private final ReplayRetentionProperties retention;

    public ChatTurnService(JdbcTemplate jdbc) { this(jdbc, new ReplayRetentionProperties()); }

    @Autowired
    public ChatTurnService(JdbcTemplate jdbc, ReplayRetentionProperties retention) {
        retention.validate();
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @PostConstruct
    public void initializeSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS psych_chat_turn (
                    conversation_id VARCHAR(64) NOT NULL REFERENCES psych_conversation(id) ON DELETE CASCADE,
                    client_msg_id VARCHAR(64) NOT NULL,
                    request_hash VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    answer TEXT NOT NULL DEFAULT '',
                    effective_mode VARCHAR(16) NOT NULL DEFAULT 'standard',
                    fallback BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (conversation_id, client_msg_id)
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_psych_running_turn ON psych_chat_turn(conversation_id) WHERE status = 'RUNNING'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_psych_turn_expiry ON psych_chat_turn(updated_at) WHERE status <> 'RUNNING'");
        // No other replica may run this application: see deployment contract. A restart must not
        // leave an unfinishable RUNNING reservation. Empty failed turns may be retried with the same key.
        jdbc.update("UPDATE psych_chat_turn SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP WHERE status = 'RUNNING'");
    }

    public record Turn(String key, String status, String answer, String mode, boolean fallback) {
        public boolean replay() { return !"RUNNING".equals(status); }
    }

    /** Replay data expires after its configured duration (default seven days), independently
     * of whole-conversation retention. This does not delete conversation history or tombstones.
     * Expiry limits duplication of sensitive answers in the idempotency cache.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void expireReplayCache() {
        retention.validate();
        long retentionMillis = retention.getReplayRetention().toMillis();
        int removed = jdbc.update("""
                DELETE FROM psych_chat_turn WHERE status <> 'RUNNING'
                  AND updated_at < CURRENT_TIMESTAMP - CAST(? AS DOUBLE PRECISION) * INTERVAL '1 millisecond'
                """, retentionMillis);
        log.info("replay_retention retentionMillis={} deleted={}", retentionMillis, removed);
    }

    @Transactional
    public Turn begin(long ownerId, String chatId, String clientMsgId, String message, boolean deep) {
        lockConversation(ownerId, chatId);
        // Covers disconnect-before-subscribe and failed terminal writes. Generation has a hard
        // 180 s deadline, so a five-minute reservation cannot still own a live answer stream.
        jdbc.update("UPDATE psych_chat_turn SET status = 'FAILED' WHERE conversation_id = ? AND status = 'RUNNING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'", chatId);
        String key = clientMsgId == null ? UUID.randomUUID().toString() : clientMsgId;
        String hash = hash(message + "\u0000" + deep);
        var previous = jdbc.query("SELECT * FROM psych_chat_turn WHERE conversation_id = ? AND client_msg_id = ?",
                (rs, row) -> new Stored(rs.getString("request_hash"), new Turn(key, rs.getString("status"),
                        rs.getString("answer"), rs.getString("effective_mode"), rs.getBoolean("fallback"))), chatId, key);
        if (!previous.isEmpty()) {
            Stored stored = previous.getFirst();
            if (!stored.hash().equals(hash)) throw conflict("同一消息标识不能用于不同内容或模式");
            if ("RUNNING".equals(stored.turn().status())) throw conflict("这条消息仍在生成，请稍后重试");
            if (!stored.turn().answer().isEmpty() || "COMPLETED".equals(stored.turn().status())) return stored.turn();
        }
        Integer running = jdbc.queryForObject("SELECT count(*) FROM psych_chat_turn WHERE conversation_id = ? AND status = 'RUNNING'", Integer.class, chatId);
        if (running != null && running > 0) throw conflict("此会话已有回答正在生成");
        jdbc.update("""
                INSERT INTO psych_chat_turn(conversation_id, client_msg_id, request_hash, status)
                VALUES (?, ?, ?, 'RUNNING')
                ON CONFLICT(conversation_id, client_msg_id) DO UPDATE SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP
                """, chatId, key, hash);
        return new Turn(key, "RUNNING", "", deep ? "deep" : "standard", false);
    }

    /** Reply and terminal state commit together. No done event may precede this transaction. */
    @Transactional
    public void finish(long ownerId, String chatId, String key, String answer, String status, String mode, boolean fallback) {
        lockConversation(ownerId, chatId);
        int updated = jdbc.update("""
                UPDATE psych_chat_turn SET answer = ?, status = ?, effective_mode = ?, fallback = ?, updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ? AND client_msg_id = ? AND status = 'RUNNING'
                """, answer, status, mode, fallback, chatId, key);
        if (updated != 1) throw conflict("轮次状态已变化，不能重复保存");
        if (!answer.isBlank()) {
            jdbc.update("INSERT INTO psych_chat_message(conversation_id, role, content) VALUES (?, 'assistant', ?)", chatId, answer);
        }
        jdbc.update("UPDATE psych_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", chatId);
    }

    private void lockConversation(long ownerId, String chatId) {
        if (jdbc.query("SELECT id FROM psych_conversation WHERE id = ? AND owner_id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), chatId, ownerId).isEmpty()) {
            throw new ConversationUnavailableException("Conversation unavailable");
        }
    }

    private record Stored(String hash, Turn turn) {}
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
