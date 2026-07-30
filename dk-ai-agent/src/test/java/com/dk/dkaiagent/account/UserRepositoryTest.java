package com.dk.dkaiagent.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号域存储层测试（冻结合约 AUTH-v1）。核心是 SQL 注入防线：一切用户输入只能以占位符 ?
 * 的绑定参数进入 SQL，绝不出现在 SQL 文本里。用 mock JdbcTemplate 校验 SQL 形状与参数分离。
 */
@ExtendWith(MockitoExtension.class)
class UserRepositoryTest {

    private static final String INJECTION = "admin' OR '1'='1";
    private static final String DROP_TABLE = "x'; DROP TABLE psych_user;--";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserRepository repository;

    @BeforeEach
    void setUp() {
        repository = new UserRepository(jdbcTemplate);
    }

    @Test
    void initializeSchemaCreatesUserTableOwnerColumnAndIndex() {
        repository.initializeSchema();

        verify(jdbcTemplate).execute(argThat((String sql) ->
                sql != null && sql.contains("CREATE TABLE IF NOT EXISTS psych_user")
                        && sql.contains("username VARCHAR(64) NOT NULL UNIQUE")
                        && sql.contains("password_hash VARCHAR(100) NOT NULL")));
        // 会话归属扩展列：ALTER TABLE ... ADD COLUMN IF NOT EXISTS owner_id。
        verify(jdbcTemplate).execute(argThat((String sql) ->
                sql != null && sql.contains("ALTER TABLE psych_conversation")
                        && sql.contains("ADD COLUMN IF NOT EXISTS owner_id")
                        && sql.contains("REFERENCES psych_user(id)")));
        verify(jdbcTemplate).execute(argThat((String sql) ->
                sql != null && sql.contains("CREATE INDEX IF NOT EXISTS idx_psych_conversation_owner_updated")
                        && sql.contains("owner_id")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByUsernamePassesInjectionAsBindParameterNotConcatenated() {
        when(jdbcTemplate.query(anyStringWithUsernameFilter(), any(RowMapper.class), eq(INJECTION)))
                .thenReturn(List.of());

        Optional<PsychUser> result = repository.findByUsername(INJECTION);

        assertTrue(result.isEmpty());
        // 注入载荷只作为绑定参数（第三个参数）出现；SQL 文本里只有占位符，没有载荷本身。
        verify(jdbcTemplate).query(
                argThat((String sql) -> sql != null
                        && sql.contains("WHERE username = ?")
                        && !sql.contains(INJECTION)),
                any(RowMapper.class),
                eq(INJECTION));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findByUsernamePassesDropTablePayloadAsBindParameter() {
        when(jdbcTemplate.query(anyStringWithUsernameFilter(), any(RowMapper.class), eq(DROP_TABLE)))
                .thenReturn(List.of());

        repository.findByUsername(DROP_TABLE);

        verify(jdbcTemplate).query(
                argThat((String sql) -> sql != null
                        && sql.contains("WHERE username = ?")
                        && !sql.contains("DROP TABLE")),
                any(RowMapper.class),
                eq(DROP_TABLE));
    }

    @Test
    void insertUserUsesReturningIdWithBoundParameters() {
        String hash = "$2a$10$abcdefghijklmnopqrstuv";
        when(jdbcTemplate.queryForObject(
                argThat((String sql) -> sql != null
                        && sql.contains("INSERT INTO psych_user")
                        && sql.contains("RETURNING id")
                        && !sql.contains("alice")
                        && !sql.contains(hash)),
                eq(Long.class),
                eq("alice"), eq(hash), eq("USER")))
                .thenReturn(42L);

        long id = repository.insertUser("alice", hash, "USER");

        assertEquals(42L, id);
    }

    @Test
    void updateStatusBindsStatusAndReason() {
        repository.updateStatus(5L, "DISABLED", "滥用 <script>");

        verify(jdbcTemplate).update(
                argThat((String sql) -> sql != null
                        && sql.contains("UPDATE psych_user")
                        && sql.contains("SET status = ?")
                        && sql.contains("disabled_reason = CASE WHEN ? = 'DISABLED' THEN ? ELSE NULL END")
                        && !sql.contains("滥用")),
                eq("DISABLED"), eq("DISABLED"), eq("DISABLED"), eq("滥用 <script>"), eq(5L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUsersFiltersKeywordAndStatusViaBindParameters() {
        when(jdbcTemplate.query(
                argThat((String sql) -> sql != null
                        && sql.contains("u.username ILIKE ?")
                        && sql.contains("u.status = ?")
                        && sql.contains("LIMIT ? OFFSET ?")
                        && !sql.contains("kw")),
                any(RowMapper.class),
                eq("%kw%"), eq("ACTIVE"), eq(20), eq(0L)))
                .thenReturn(List.of());

        repository.listUsers("kw", "active", 0, 20);

        verify(jdbcTemplate).query(
                argThat((String sql) -> sql != null && sql.contains("u.username ILIKE ?")),
                any(RowMapper.class),
                eq("%kw%"), eq("ACTIVE"), eq(20), eq(0L));
    }

    @Test
    void adoptOrphanConversationsBindsOwnerAndFiltersNullOwner() {
        when(jdbcTemplate.update(
                argThat((String sql) -> sql != null
                        && sql.contains("UPDATE psych_conversation SET owner_id = ?")
                        && sql.contains("WHERE owner_id IS NULL")),
                eq(9L)))
                .thenReturn(3);

        assertEquals(3, repository.adoptOrphanConversations(9L));
    }

    @Test
    void deleteByIdRemovesConversationsBeforeUserRow() {
        when(jdbcTemplate.update(
                argThat((String sql) -> sql != null
                        && sql.contains("INSERT INTO psych_conversation_tombstone")),
                eq(5L)))
                .thenReturn(2);
        when(jdbcTemplate.update(
                argThat((String sql) -> sql != null
                        && sql.contains("DELETE FROM psych_conversation WHERE owner_id = ?")),
                eq(5L)))
                .thenReturn(2);
        when(jdbcTemplate.update(
                argThat((String sql) -> sql != null
                        && sql.contains("DELETE FROM psych_user WHERE id = ?")),
                eq(5L)))
                .thenReturn(1);

        assertTrue(repository.deleteById(5L));

        // 先对其会话补墓碑，再级联删会话，最后删用户行：
        // FK 无 ON DELETE CASCADE，顺序颠倒会违反外键；墓碑防在途流 bootstrap 复活已删会话。
        InOrder ordered = inOrder(jdbcTemplate);
        ordered.verify(jdbcTemplate).update(
                argThat((String sql) -> sql != null && sql.contains("INSERT INTO psych_conversation_tombstone")),
                eq(5L));
        ordered.verify(jdbcTemplate).update(
                argThat((String sql) -> sql != null && sql.contains("DELETE FROM psych_conversation")),
                eq(5L));
        ordered.verify(jdbcTemplate).update(
                argThat((String sql) -> sql != null && sql.contains("DELETE FROM psych_user")),
                eq(5L));
    }

    private static String anyStringWithUsernameFilter() {
        return org.mockito.ArgumentMatchers.argThat(
                (String sql) -> sql != null && sql.contains("WHERE username = ?"));
    }
}
