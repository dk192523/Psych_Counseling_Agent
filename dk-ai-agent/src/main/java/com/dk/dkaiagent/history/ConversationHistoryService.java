package com.dk.dkaiagent.history;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationHistoryService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_CONVERSATION_LIST_SIZE = 50;

    private static final RowMapper<ConversationMessage> MESSAGE_ROW_MAPPER = (rs, rowNum) -> new ConversationMessage(
            rs.getLong("id"),
            rs.getString("role"),
            rs.getString("content"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static final RowMapper<ConversationSummary> SUMMARY_ROW_MAPPER = (rs, rowNum) -> new ConversationSummary(
            rs.getString("id"),
            rs.getString("title"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getInt("message_count"),
            rs.getString("preview"),
            rs.getInt("max_messages")
    );

    private final JdbcTemplate jdbcTemplate;
    private final int maxMessagesPerConversation;
    private final int titleMaxLength;

    public ConversationHistoryService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.chat-history.max-messages-per-conversation:1000}") int maxMessagesPerConversation,
            @Value("${app.chat-history.title-max-length:30}") int titleMaxLength) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxMessagesPerConversation = requirePositive(maxMessagesPerConversation,
                "app.chat-history.max-messages-per-conversation");
        this.titleMaxLength = requirePositive(titleMaxLength, "app.chat-history.title-max-length");
    }

    @PostConstruct
    void initializeSchema() {
        // 归属列 owner_id 由 UserRepository 的 ALTER TABLE 追加（AUTH-v1 冻结合约）：psych_user
        // 在本 Bean 之后建表（UserRepository @DependsOn 本服务），此处的建表语句无法前向引用其 FK。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS psych_conversation (
                    id VARCHAR(64) PRIMARY KEY,
                    title VARCHAR(120) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS psych_chat_message (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id VARCHAR(64) NOT NULL
                        REFERENCES psych_conversation(id) ON DELETE CASCADE,
                    role VARCHAR(16) NOT NULL,
                    content TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS psych_conversation_memory (
                    conversation_id VARCHAR(64) PRIMARY KEY REFERENCES psych_conversation(id) ON DELETE CASCADE,
                    digest TEXT NOT NULL DEFAULT '',
                    covered_until_message_id BIGINT NOT NULL DEFAULT 0,
                    covered_message_count INT NOT NULL DEFAULT 0,
                    digest_chars INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_psych_chat_message_conversation_id_id
                ON psych_chat_message (conversation_id, id)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_psych_conversation_updated_at
                ON psych_conversation (updated_at DESC)
                """);
        // 删除墓碑：随 delete() 同事务写入，使聊天流 bootstrap 能区分"从未创建"与"刚被并发删除"，
        // 对已删 id 永久拒绝复活。刻意不加 psych_conversation 的 FK——墓碑正是在会话删除时写入的。
        // UUID 永不复用，墓碑无需清理（仅容量问题，介意可按 deleted_at 定期清扫）。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS psych_conversation_tombstone (
                    conversation_id VARCHAR(64) PRIMARY KEY,
                    owner_id BIGINT NOT NULL,
                    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    public ConversationSummary createConversation(long ownerId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO psych_conversation (id, title, owner_id, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, DEFAULT_TITLE, ownerId);
        return findConversationSummary(id, ownerId).orElseThrow();
    }

    public List<ConversationSummary> listConversations(long ownerId) {
        return jdbcTemplate.query("""
                SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count,
                       (
                           SELECT SUBSTRING(latest.content FROM 1 FOR 60)
                           FROM psych_chat_message latest
                           WHERE latest.conversation_id = c.id
                           ORDER BY latest.id DESC
                           LIMIT 1
                       ) AS preview,
                       ? AS max_messages
                FROM psych_conversation c
                LEFT JOIN psych_chat_message m ON m.conversation_id = c.id
                WHERE c.owner_id = ?
                GROUP BY c.id, c.title, c.created_at, c.updated_at
                ORDER BY c.updated_at DESC, c.id DESC
                LIMIT ?
                """, SUMMARY_ROW_MAPPER, maxMessagesPerConversation, ownerId, MAX_CONVERSATION_LIST_SIZE);
    }

    public Optional<ConversationDetail> getConversation(String chatId, long ownerId) {
        String normalizedChatId = requireText(chatId, "chatId");
        // owner 过滤即跨用户隔离：他人会话与不存在同形（空 Optional），控制器统一转 404，不以 403 泄露存在性。
        List<ConversationSummary> conversations = jdbcTemplate.query("""
                SELECT c.id, c.title, c.created_at, c.updated_at, COUNT(m.id) AS message_count,
                       (
                           SELECT SUBSTRING(latest.content FROM 1 FOR 60)
                           FROM psych_chat_message latest
                           WHERE latest.conversation_id = c.id
                           ORDER BY latest.id DESC
                           LIMIT 1
                       ) AS preview,
                       ? AS max_messages
                FROM psych_conversation c
                LEFT JOIN psych_chat_message m ON m.conversation_id = c.id
                WHERE c.id = ? AND c.owner_id = ?
                GROUP BY c.id, c.title, c.created_at, c.updated_at
                """, SUMMARY_ROW_MAPPER, maxMessagesPerConversation, normalizedChatId, ownerId);
        if (conversations.isEmpty()) {
            return Optional.empty();
        }

        ConversationSummary conversation = conversations.getFirst();
        List<ConversationMessage> messages = jdbcTemplate.query("""
                SELECT id, role, content, created_at
                FROM psych_chat_message
                WHERE conversation_id = ?
                ORDER BY id ASC
                """, MESSAGE_ROW_MAPPER, normalizedChatId);
        MemoryStats memory = buildMemoryStats(normalizedChatId, conversation.messageCount());
        return Optional.of(new ConversationDetail(
                conversation.id(),
                conversation.title(),
                conversation.createdAt(),
                conversation.updatedAt(),
                List.copyOf(messages),
                memory
        ));
    }

    @Transactional
    public void appendUserMessage(long ownerId, String chatId, String content) {
        appendMessage(ownerId, chatId, "user", content, true);
    }

    /**
     * Appends an assistant reply. Returns the number of inserted messages: {@code 0} means the
     * conversation was deleted (cascade commit landed) while the reply was still streaming, so the
     * caller must skip memory consolidation. Never resurrects the parent conversation row.
     * Throws {@link IllegalStateException} when the conversation exists but belongs to another
     * owner, so a stream can never write into someone else's conversation.
     */
    @Transactional
    public int appendAssistantMessage(long ownerId, String chatId, String content) {
        return appendMessage(ownerId, chatId, "assistant", content, false);
    }

    public List<Message> getRecentMessages(String chatId, int limit) {
        String normalizedChatId = requireText(chatId, "chatId");
        if (limit <= 0) {
            return List.of();
        }
        int effectiveLimit = Math.min(limit, maxMessagesPerConversation);
        List<Message> newestFirst = jdbcTemplate.query("""
                SELECT role, content
                FROM psych_chat_message
                WHERE conversation_id = ? AND role IN ('user', 'assistant')
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> switch (rs.getString("role")) {
            case "user" -> new UserMessage(rs.getString("content"));
            case "assistant" -> new AssistantMessage(rs.getString("content"));
            default -> throw new IllegalStateException("Unsupported chat role");
        }, normalizedChatId, effectiveLimit);

        List<Message> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    public String getDigest(String chatId) {
        String normalizedChatId = requireText(chatId, "chatId");
        return findMemoryRow(normalizedChatId).map(MemoryRow::digest).orElse("");
    }

    public long getCoveredUntilMessageId(String chatId) {
        String normalizedChatId = requireText(chatId, "chatId");
        return findCoveredUntilMessageId(normalizedChatId);
    }

    public int countMessages(String chatId) {
        String normalizedChatId = requireText(chatId, "chatId");
        return countRawMessages(normalizedChatId);
    }

    public List<ConversationMessage> getUncoveredMessages(String chatId, int limit) {
        String normalizedChatId = requireText(chatId, "chatId");
        if (limit <= 0) {
            return List.of();
        }
        long coveredUntil = findCoveredUntilMessageId(normalizedChatId);
        return jdbcTemplate.query("""
                SELECT id, role, content, created_at
                FROM psych_chat_message
                WHERE conversation_id = ? AND id > ?
                ORDER BY id ASC
                LIMIT ?
                """, MESSAGE_ROW_MAPPER, normalizedChatId, coveredUntil, limit);
    }

    public List<ConversationMessage> getOldestMessages(String chatId, int limit) {
        String normalizedChatId = requireText(chatId, "chatId");
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT id, role, content, created_at
                FROM psych_chat_message
                WHERE conversation_id = ?
                ORDER BY id ASC
                LIMIT ?
                """, MESSAGE_ROW_MAPPER, normalizedChatId, limit);
    }

    public List<ConversationMessage> searchRecallCandidates(String chatId, String keyword, int limit) {
        String normalizedChatId = requireText(chatId, "chatId");
        if (limit <= 0) {
            return List.of();
        }
        String pattern = "%" + (keyword == null ? "" : keyword) + "%";
        return jdbcTemplate.query("""
                SELECT id, role, content, created_at
                FROM psych_chat_message
                WHERE conversation_id = ? AND role IN ('user', 'assistant')
                ORDER BY (content ILIKE ?) DESC, id DESC
                LIMIT ?
                """, MESSAGE_ROW_MAPPER, normalizedChatId, pattern, limit);
    }

    public MemoryStats getMemoryStats(String chatId) {
        String normalizedChatId = requireText(chatId, "chatId");
        return buildMemoryStats(normalizedChatId, countRawMessages(normalizedChatId));
    }

    @Transactional
    public void replaceMemoryAndPrune(
            String chatId,
            String digest,
            long coveredUntilMessageId,
            int coveredMessageCount,
            long pruneUpToMessageId) {
        String normalizedChatId = requireText(chatId, "chatId");
        String normalizedDigest = digest == null ? "" : digest;
        // compare-and-set：进程内的 per-chat 锁挡不住共享同一库的第二个 JVM。WHERE 使水位只能
        // 前进——过期写者（covered_until 更小）整条更新被跳过且语句返回 0 行，自动成为 no-op，
        // 避免用只见过旧批次的 digest 覆盖胜者、造成胜者已剪枝消息永久丢失。
        int upserted = jdbcTemplate.update("""
                INSERT INTO psych_conversation_memory (
                    conversation_id, digest, covered_until_message_id, covered_message_count, digest_chars, updated_at
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    digest = EXCLUDED.digest,
                    covered_until_message_id = EXCLUDED.covered_until_message_id,
                    covered_message_count = EXCLUDED.covered_message_count,
                    digest_chars = EXCLUDED.digest_chars,
                    updated_at = CURRENT_TIMESTAMP
                WHERE psych_conversation_memory.covered_until_message_id < EXCLUDED.covered_until_message_id
                """, normalizedChatId, normalizedDigest, coveredUntilMessageId, coveredMessageCount,
                normalizedDigest.length());
        // 只有真正推进了水位的写者才剪枝：保持"剪枝范围必被当前 digest 覆盖"的不变量闭合。
        if (upserted > 0) {
            jdbcTemplate.update("""
                    DELETE FROM psych_chat_message
                    WHERE conversation_id = ? AND id <= ?
                    """, normalizedChatId, pruneUpToMessageId);
        }
    }

    @Transactional
    public void clear(String chatId, long ownerId) {
        String normalizedChatId = requireText(chatId, "chatId");
        // 全部带 owner 过滤：跨用户清空是对他人会话的静默 no-op，与 delete 的 404 语义对称，不泄露存在性。
        jdbcTemplate.update("""
                DELETE FROM psych_chat_message
                WHERE conversation_id IN (SELECT id FROM psych_conversation WHERE id = ? AND owner_id = ?)
                """, normalizedChatId, ownerId);
        jdbcTemplate.update("""
                DELETE FROM psych_conversation_memory
                WHERE conversation_id IN (SELECT id FROM psych_conversation WHERE id = ? AND owner_id = ?)
                """, normalizedChatId, ownerId);
        jdbcTemplate.update("""
                UPDATE psych_conversation
                SET title = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ?
                """, DEFAULT_TITLE, normalizedChatId, ownerId);
    }

    @Transactional
    public boolean delete(String chatId, long ownerId) {
        // psych_chat_message 与 psych_conversation_memory 均通过 ON DELETE CASCADE 随会话一并清理。
        // owner 过滤：跨用户删除返回 false，控制器转 404，与"不存在"同形。
        String normalizedChatId = requireText(chatId, "chatId");
        boolean deleted = jdbcTemplate.update(
                "DELETE FROM psych_conversation WHERE id = ? AND owner_id = ?",
                normalizedChatId, ownerId) > 0;
        if (deleted) {
            // 同事务补墓碑：堵住"校验通过后被并发删除"时 bootstrap 把"行不存在"误读为"从未创建"
            // 而复活已删会话的路径（见 appendMessage 用户路径的条件插入）。
            jdbcTemplate.update("""
                    INSERT INTO psych_conversation_tombstone (conversation_id, owner_id)
                    VALUES (?, ?)
                    ON CONFLICT (conversation_id) DO UPDATE SET deleted_at = CURRENT_TIMESTAMP
                    """, normalizedChatId, ownerId);
        }
        return deleted;
    }

    private int appendMessage(long ownerId, String chatId, String role, String content, boolean updateTitle) {
        // 原文档案只做追加，不再滑动窗口硬删除；淘汰由记忆服务整合成功后调用 replaceMemoryAndPrune 触发。
        String normalizedChatId = requireText(chatId, "chatId");
        String normalizedContent = requireContent(content);
        String initialTitle = updateTitle ? buildTitle(normalizedContent) : DEFAULT_TITLE;

        int inserted;
        if (updateTitle) {
            // 用户消息路径是合法的会话 bootstrap：直连聊天调用方可能没有先 POST /ai/conversations。
            // 创建时必须写 owner_id；冲突不落行，归属交由下面的守卫统一裁决。
            // 墓碑条件：对已被并发删除的 id（delete() 同事务已写墓碑）原子地不落行——随后的
            // requireConversationOwnedBy 查不到行即抛异常（控制器 404），已删会话绝不复活。
            jdbcTemplate.update("""
                    INSERT INTO psych_conversation (id, title, owner_id, created_at, updated_at)
                    SELECT ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    WHERE NOT EXISTS (
                        SELECT 1 FROM psych_conversation_tombstone t
                        WHERE t.conversation_id = ?
                    )
                    ON CONFLICT (id) DO NOTHING
                    """, normalizedChatId, initialTitle, ownerId, normalizedChatId);
            // 零窗口墓碑复核：闭合"快照 vs 冲突等待"缝隙——条件插入内的 NOT EXISTS 快照先于唯一索引
            // 冲突阻塞求值，若并发删除事务在我们等待冲突期间提交，NOT EXISTS 已过期、插入会实体化并复活
            // 刚被删除的会话（同一竞态对 deleteById 还会经 owner_id FK 锁升级为死锁，Postgres 牺牲一方后
            // 状态仍一致：要么本事务被中止（500，重试即成功），要么删除方被中止（会话本就没删成）。
            // 无竞态论证依赖 READ COMMITTED（当前配置默认隔离级别；若未来提升至 REPEATABLE READ，
            // 本语句将复用事务快照、论证失效，须重审）：本插入能实体化的唯一途径是等完并发删除者，
            // 而 delete() 与 deleteById 两条删除路径都在提交前同事务写入墓碑，故实体化瞬间墓碑已提交；
            // 本复核是新语句、新快照，必见该墓碑。DO NOTHING 分支未落行、不持锁，随后
            // requireConversationOwnedBy 已覆盖"更晚提交的删除"（行消失 -> 抛异常 -> 回滚）。
            // 抛出的 IllegalStateException 由 @Transactional 回滚投机插入的行，已删会话绝不复活，
            // 并由控制器统一转 404（与"不存在"同形）。
            if (tombstoneExists(normalizedChatId)) {
                throw new IllegalStateException("Conversation was deleted concurrently");
            }
            // 会话已存在但归属他人：抛异常由控制器转 404，堵住跨用户写入与幽灵复活路径。
            requireConversationOwnedBy(normalizedChatId, ownerId);
            jdbcTemplate.update("""
                    UPDATE psych_conversation c
                    SET title = ?
                    WHERE c.id = ?
                      AND c.title = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM psych_chat_message m
                          WHERE m.conversation_id = c.id AND m.role = 'user'
                      )
                    """, initialTitle, normalizedChatId, DEFAULT_TITLE);
            inserted = jdbcTemplate.update("""
                    INSERT INTO psych_chat_message (conversation_id, role, content, created_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """, normalizedChatId, role, normalizedContent);
        } else {
            // 助手路径绝不 upsert 父行：用户删会话后仍在收尾的流式回答若盲写，会把已删会话
            // 以"新会话"复活并留下孤儿消息。单语句 EXISTS 守卫与 DELETE 提交原子——删除先提交
            // 则 EXISTS 为假，不落任何行，调用方按返回 0 跳过后续整合。
            // 会话存在但归属他人时抛异常（控制器转 404），杜绝流式写入跨用户会话的幽灵路径。
            requireConversationOwnedByIfExists(normalizedChatId, ownerId);
            inserted = jdbcTemplate.update("""
                    INSERT INTO psych_chat_message (conversation_id, role, content, created_at)
                    SELECT ?, ?, ?, CURRENT_TIMESTAMP
                    WHERE EXISTS (SELECT 1 FROM psych_conversation WHERE id = ?)
                    """, normalizedChatId, role, normalizedContent, normalizedChatId);
        }

        jdbcTemplate.update("""
                UPDATE psych_conversation
                SET updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_id = ?
                """, normalizedChatId, ownerId);
        return inserted;
    }

    /**
     * 归属守卫（用户路径）：会话不存在或不属于 ownerId 一律抛 {@link IllegalStateException}，
     * 由控制器转 404，与"不存在"同形。
     */
    private void requireConversationOwnedBy(String chatId, long ownerId) {
        Long currentOwner = findConversationOwner(chatId).orElse(null);
        if (currentOwner == null || currentOwner.longValue() != ownerId) {
            throw new IllegalStateException("Conversation is not owned by the current user");
        }
    }

    /**
     * 归属守卫（助手路径）：会话尚不存在时放行（EXISTS 守卫会使插入原子地不落行，保持
     * "流式期间被删返回 0"语义）；存在但归属他人则抛异常。
     */
    private void requireConversationOwnedByIfExists(String chatId, long ownerId) {
        findConversationOwner(chatId).ifPresent(currentOwner -> {
            if (currentOwner.longValue() != ownerId) {
                throw new IllegalStateException("Conversation is not owned by the current user");
            }
        });
    }

    /**
     * 会话归属查询。行不存在与 owner_id 为 NULL（遗留数据未完成归属迁移）统一收敛为
     * Optional.empty，即"当前调用方无主"；AdminBootstrap 的启动迁移先于请求流量完成，
     * NULL 只是兜底防线。
     */
    private Optional<Long> findConversationOwner(String chatId) {
        List<Long> owners = jdbcTemplate.query("""
                SELECT owner_id
                FROM psych_conversation
                WHERE id = ?
                """, (rs, rowNum) -> (Long) rs.getObject("owner_id"), chatId);
        return owners.isEmpty() ? Optional.empty() : Optional.ofNullable(owners.getFirst());
    }

    /**
     * 墓碑存在性复核：appendMessage 用户路径 bootstrap 插入后的零窗口再校验（见调用处注释）。
     * 独立语句在 READ COMMITTED 下取全新快照，必见并发删除者已提交的墓碑。
     */
    private boolean tombstoneExists(String chatId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM psych_conversation_tombstone WHERE conversation_id = ?",
                Integer.class, chatId);
        return count != null && count > 0;
    }

    private MemoryStats buildMemoryStats(String chatId, int messageCount) {
        Optional<MemoryRow> memoryRow = findMemoryRow(chatId);
        return new MemoryStats(
                messageCount,
                maxMessagesPerConversation,
                memoryRow.map(MemoryRow::coveredMessageCount).orElse(0),
                memoryRow.map(MemoryRow::digestChars).orElse(0),
                memoryRow.map(MemoryRow::digest).orElse(""),
                memoryRow.map(MemoryRow::updatedAt).orElse(null)
        );
    }

    private Optional<MemoryRow> findMemoryRow(String chatId) {
        List<MemoryRow> rows = jdbcTemplate.query("""
                SELECT digest, covered_message_count, digest_chars, updated_at
                FROM psych_conversation_memory
                WHERE conversation_id = ?
                """, (rs, rowNum) -> new MemoryRow(
                rs.getString("digest"),
                rs.getInt("covered_message_count"),
                rs.getInt("digest_chars"),
                toInstant(rs.getTimestamp("updated_at"))
        ), chatId);
        return rows.stream().findFirst();
    }

    private long findCoveredUntilMessageId(String chatId) {
        List<Long> watermarks = jdbcTemplate.query("""
                SELECT covered_until_message_id
                FROM psych_conversation_memory
                WHERE conversation_id = ?
                """, (rs, rowNum) -> rs.getLong("covered_until_message_id"), chatId);
        return watermarks.stream().findFirst().orElse(0L);
    }

    private int countRawMessages(String chatId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM psych_chat_message
                WHERE conversation_id = ?
                """, Long.class, chatId);
        return count == null ? 0 : count.intValue();
    }

    private Optional<ConversationSummary> findConversationSummary(String chatId, long ownerId) {
        List<ConversationSummary> result = jdbcTemplate.query("""
                SELECT id, title, created_at, updated_at, 0 AS message_count, NULL AS preview,
                       ? AS max_messages
                FROM psych_conversation
                WHERE id = ? AND owner_id = ?
                """, SUMMARY_ROW_MAPPER, maxMessagesPerConversation, chatId, ownerId);
        return result.stream().findFirst();
    }

    private String buildTitle(String content) {
        String singleLine = content.replaceAll("\\s+", " ").trim();
        int codePointCount = singleLine.codePointCount(0, singleLine.length());
        if (codePointCount <= titleMaxLength) {
            return singleLine;
        }
        int end = singleLine.offsetByCodePoints(0, titleMaxLength);
        return singleLine.substring(0, end) + "…";
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }
        return value;
    }

    private static Instant toInstant(Timestamp value) {
        return value.toInstant();
    }

    record MemoryRow(String digest, int coveredMessageCount, int digestChars, Instant updatedAt) {
    }
}
