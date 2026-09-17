package com.dk.dkaiagent.history;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL/transaction tests without SpringBootTest, embeddings, a worker or any model keys. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ChatTurnPostgresTest {
    JdbcTemplate jdbc;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    ChatTurnService turns;
    String schema;
    @BeforeEach void setup() {
        String url = System.getenv("TEST_DATABASE_URL");
        String user = System.getenv().getOrDefault("TEST_DATABASE_USER", "postgres");
        String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "repair_test_only");
        schema = "repair_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var history = new ConversationHistoryService(jdbc, 1000, 30);
        history.initializeSchema();
        jdbc.execute("ALTER TABLE psych_conversation ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 1");
        jdbc.update("INSERT INTO psych_conversation(id,title,owner_id) VALUES ('chat','test',1)");
        turns = new ChatTurnService(jdbc);
        turns.initializeSchema();
    }
    @AfterEach void cleanup() {
        if (schema != null && schema.matches("repair_test_[a-f0-9]+")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    ChatTurnService.Turn begin(String key, String text) { return transaction.execute(s -> turns.begin(1, "chat", key, text, false)); }
    void finish(String text) { transaction.executeWithoutResult(s -> turns.finish(1, "chat", "key", text, "COMPLETED", "standard", false)); }
    @Test void completeReplayAndConflictingPayload() {
        begin("key", "hello"); finish("answer");
        assertEquals("answer", begin("key", "hello").answer());
        assertThrows(ResponseStatusException.class, () -> begin("key", "changed"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM psych_chat_message", Integer.class));
    }
    @Test void failedInsertRollsBackTerminalState() {
        begin("key", "hello");
        jdbc.execute("ALTER TABLE psych_chat_message ADD CONSTRAINT fail_reply CHECK (role <> 'assistant')");
        assertThrows(RuntimeException.class, () -> finish("answer"));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM psych_chat_turn", String.class));
    }
    @Test void concurrentRequestsHaveExactlyOneWinner() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            Callable<Boolean> task = () -> { ready.countDown(); go.await(); try { begin("key", "hello"); return true; } catch (ResponseStatusException e) { return false; } };
            var a = pool.submit(task); var b = pool.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }
    @Test void anotherTurnIsBlockedWhileOneIsRunning() {
        begin("key", "hello");
        assertThrows(ResponseStatusException.class, () -> begin("other", "next"));
    }
    @Test void deletionCascadesAndCannotBeResurrected() {
        begin("key", "hello"); jdbc.update("DELETE FROM psych_conversation WHERE id='chat'");
        assertThrows(ConversationUnavailableException.class, () -> finish("answer"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM psych_chat_turn", Integer.class));
    }
    @Test void foreignOwnerCannotReserveOrSave() {
        assertThrows(ConversationUnavailableException.class, () -> transaction.execute(s -> turns.begin(2, "chat", "key", "hello", false)));
        begin("key", "hello");
        assertThrows(ConversationUnavailableException.class, () -> transaction.executeWithoutResult(s -> turns.finish(2, "chat", "key", "answer", "COMPLETED", "standard", false)));
    }
    @Test void emptyFailedTurnCanRetryButPartialReplyIsImmutable() {
        begin("key", "hello");
        transaction.executeWithoutResult(s -> turns.finish(1,"chat","key","","FAILED","standard",false));
        assertFalse(begin("key", "hello").replay());
        transaction.executeWithoutResult(s -> turns.finish(1,"chat","key","part","CANCELLED","standard",false));
        assertEquals("part", begin("key", "hello").answer());
    }
    @Test void replayCacheExpiresWithoutDeletingConversationHistory() {
        begin("key", "hello"); finish("answer");
        jdbc.update("UPDATE psych_chat_turn SET updated_at = CURRENT_TIMESTAMP - INTERVAL '8 days'");
        turns.expireReplayCache();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM psych_chat_turn", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM psych_chat_message", Integer.class));
    }
}
