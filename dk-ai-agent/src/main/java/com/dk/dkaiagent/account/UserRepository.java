package com.dk.dkaiagent.account;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 账号域存储层（冻结合约 AUTH-v1）。全部 SQL 走参数占位符，用户输入绝不拼接。
 * owner_id 扩展列与索引在此建；@DependsOn 保证 psych_conversation 已被
 * ConversationHistoryService 的 @PostConstruct 先行建好，再执行 ALTER TABLE。
 */
@Repository
@DependsOn("conversationHistoryService")
public class UserRepository {

    private static final int DISABLED_REASON_MAX_LENGTH = 200;

    private static final RowMapper<PsychUser> USER_ROW_MAPPER = (rs, rowNum) -> new PsychUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstantOrNull(rs.getTimestamp("last_login_at")),
            toInstantOrNull(rs.getTimestamp("disabled_at")),
            rs.getString("disabled_reason")
    );

    private static final RowMapper<UserListRow> USER_LIST_ROW_MAPPER = (rs, rowNum) -> new UserListRow(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("role"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstantOrNull(rs.getTimestamp("last_login_at")),
            toInstantOrNull(rs.getTimestamp("disabled_at")),
            rs.getString("disabled_reason"),
            rs.getLong("conversation_count")
    );

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initializeSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS psych_user (
                    id BIGSERIAL PRIMARY KEY,
                    username VARCHAR(64) NOT NULL UNIQUE,
                    password_hash VARCHAR(100) NOT NULL,
                    role VARCHAR(16) NOT NULL DEFAULT 'USER',
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_login_at TIMESTAMPTZ,
                    disabled_at TIMESTAMPTZ,
                    disabled_reason VARCHAR(200)
                )
                """);
        // 会话归属扩展列：FK 默认 NO ACTION，删除用户前由 deleteById 先级联清理其会话。
        jdbcTemplate.execute("""
                ALTER TABLE psych_conversation
                ADD COLUMN IF NOT EXISTS owner_id BIGINT REFERENCES psych_user(id)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_psych_conversation_owner_updated
                ON psych_conversation (owner_id, updated_at DESC)
                """);
    }

    public Optional<PsychUser> findByUsername(String username) {
        List<PsychUser> users = jdbcTemplate.query("""
                SELECT id, username, password_hash, role, status, created_at, updated_at,
                       last_login_at, disabled_at, disabled_reason
                FROM psych_user
                WHERE username = ?
                """, USER_ROW_MAPPER, username);
        return users.stream().findFirst();
    }

    public Optional<PsychUser> findById(long id) {
        List<PsychUser> users = jdbcTemplate.query("""
                SELECT id, username, password_hash, role, status, created_at, updated_at,
                       last_login_at, disabled_at, disabled_reason
                FROM psych_user
                WHERE id = ?
                """, USER_ROW_MAPPER, id);
        return users.stream().findFirst();
    }

    public long insertUser(String username, String passwordHash, String role) {
        Long generatedId = jdbcTemplate.queryForObject("""
                INSERT INTO psych_user (username, password_hash, role, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, username, passwordHash, role);
        if (generatedId == null) {
            throw new IllegalStateException("insertUser returned no generated id");
        }
        return generatedId;
    }

    public int updatePasswordHash(long id, String passwordHash) {
        return jdbcTemplate.update("""
                UPDATE psych_user
                SET password_hash = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, passwordHash, id);
    }

    public int updateStatus(long id, String status, String reason) {
        // CASE 内判定而非传 null 时间戳，规避 setNull(TYPE_UNKNOWN) 的类型歧义；
        // 启用时 disabled_at / disabled_reason 一并置空，状态语义闭合。
        String normalizedReason = truncateReason(reason);
        return jdbcTemplate.update("""
                UPDATE psych_user
                SET status = ?,
                    disabled_at = CASE WHEN ? = 'DISABLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    disabled_reason = CASE WHEN ? = 'DISABLED' THEN ? ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, status, status, normalizedReason == null ? "" : normalizedReason, id);
    }

    public int updateLastLogin(long id) {
        return jdbcTemplate.update("""
                UPDATE psych_user
                SET last_login_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, id);
    }

    public List<UserListRow> listUsers(String keyword, String status, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.username, u.role, u.status, u.created_at, u.last_login_at,
                       u.disabled_at, u.disabled_reason, COUNT(c.id) AS conversation_count
                FROM psych_user u
                LEFT JOIN psych_conversation c ON c.owner_id = u.id
                """);
        List<Object> params = new ArrayList<>(appendFilters(sql, keyword, status));
        sql.append("""
                GROUP BY u.id
                ORDER BY u.id ASC
                LIMIT ? OFFSET ?
                """);
        params.add(size);
        params.add((long) page * size);
        return jdbcTemplate.query(sql.toString(), USER_LIST_ROW_MAPPER, params.toArray());
    }

    public long countUsers(String keyword, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM psych_user u
                """);
        List<Object> params = new ArrayList<>(appendFilters(sql, keyword, status));
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM psych_user", Long.class);
        return count == null ? 0 : count;
    }

    public long countByRole(String role) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM psych_user WHERE role = ?", Long.class, role);
        return count == null ? 0 : count;
    }

    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM psych_user WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    public long countConversations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM psych_conversation", Long.class);
        return count == null ? 0 : count;
    }

    public long countMessages() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM psych_chat_message", Long.class);
        return count == null ? 0 : count;
    }

    /** 遗留数据一次性迁移：无主会话归属到超管（冻结合约 AUTH-v1）。 */
    public int adoptOrphanConversations(long ownerId) {
        return jdbcTemplate.update(
                "UPDATE psych_conversation SET owner_id = ? WHERE owner_id IS NULL", ownerId);
    }

    @Transactional
    public boolean deleteById(long id) {
        // psych_user 的 FK 无 ON DELETE CASCADE：先删其会话，消息/记忆随 psych_conversation
        // 自身的 ON DELETE CASCADE 级联清理；会话级 HttpSession 由控制器/安全层另行销毁。
        // 删除前对其全部会话补墓碑（同事务）：防止在途聊天流的 bootstrap 以旧 owner_id 复活
        // 已删会话（复活还会因 owner_id FK 指向已删用户而 500）。
        jdbcTemplate.update("""
                INSERT INTO psych_conversation_tombstone (conversation_id, owner_id)
                SELECT id, owner_id FROM psych_conversation WHERE owner_id = ?
                ON CONFLICT (conversation_id) DO UPDATE SET deleted_at = CURRENT_TIMESTAMP
                """, id);
        jdbcTemplate.update("DELETE FROM psych_conversation WHERE owner_id = ?", id);
        return jdbcTemplate.update("DELETE FROM psych_user WHERE id = ?", id) > 0;
    }

    private static List<Object> appendFilters(StringBuilder sql, String keyword, String status) {
        // WHERE 片段仅为固定常量谓词；keyword/status 值一律经占位符进入 SQL。
        List<Object> params = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            predicates.add("u.username ILIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            predicates.add("u.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (!predicates.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", predicates)).append('\n');
        }
        return params;
    }

    private static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() <= DISABLED_REASON_MAX_LENGTH) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0,
                Math.min(DISABLED_REASON_MAX_LENGTH, trimmed.codePointCount(0, trimmed.length())));
        return trimmed.substring(0, end);
    }

    private static Instant toInstant(Timestamp value) {
        return value.toInstant();
    }

    private static Instant toInstantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 管理端用户列表行；conversationCount 来自 LEFT JOIN。控制器直接映射 DTO（不含 password_hash）。 */
    public record UserListRow(
            long id,
            String username,
            String role,
            String status,
            Instant createdAt,
            Instant lastLoginAt,
            Instant disabledAt,
            String disabledReason,
            long conversationCount
    ) {
    }
}
