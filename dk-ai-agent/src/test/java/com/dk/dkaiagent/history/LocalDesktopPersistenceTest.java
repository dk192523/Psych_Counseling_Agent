package com.dk.dkaiagent.history;

import com.dk.dkaiagent.account.UserRepository;
import com.dk.dkaiagent.config.JdbcDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real embedded SQL and transactions, without HTTP, model downloads or a PostgreSQL server. */
class LocalDesktopPersistenceTest {
    JdbcTemplate jdbc;
    ConversationHistoryService history;
    UserRepository users;
    ChatTurnService turns;
    TransactionTemplate transaction;
    DataSourceTransactionManager manager;
    long owner;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:desktop_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        history = new ConversationHistoryService(jdbc, 1000, 30, JdbcDialect.H2);
        history.initializeSchema();
        users = new UserRepository(jdbc, JdbcDialect.H2);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(users, "initializeSchema");
        owner = users.insertUser("desktop", "not-a-real-password", "USER");
        turns = new ChatTurnService(jdbc, new ReplayRetentionProperties(), JdbcDialect.H2);
        turns.initializeSchema();
    }

    @org.junit.jupiter.api.AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    ChatTurnService.Turn begin(String chat, String key, String message) {
        return transaction.execute(status -> turns.begin(owner, chat, key, message, false));
    }
    void finish(String chat, String key, String reply) {
        transaction.executeWithoutResult(status -> turns.finish(owner, chat, key, reply, "COMPLETED", "standard", false));
    }
    String chat() { return history.createConversation(owner).id(); }

    @Test void bootstrapDeduplicatesNullKeysRemainDistinctAndTombstoneBlocksResurrection() {
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> history.appendUserMessage(owner, "chat", "hello", "key"))));
        assertFalse(Boolean.TRUE.equals(transaction.execute(status -> history.appendUserMessage(owner, "chat", "hello", "key"))));
        transaction.executeWithoutResult(status -> history.appendUserMessage(owner, "chat", "next"));
        transaction.executeWithoutResult(status -> history.appendUserMessage(owner, "chat", "next"));
        assertEquals(3, history.countMessages("chat"));
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> history.delete("chat", owner))));
        assertThrows(ConversationUnavailableException.class, () -> transaction.execute(status -> history.appendUserMessage(owner, "chat", "again", "new")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM psych_conversation", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM psych_conversation_tombstone", Integer.class));
    }

    @Test void completeReplaysButChangedPayloadAndOtherOwnerCannotReserve() {
        String chat = chat();
        begin(chat, "key", "hello"); finish(chat, "key", "answer");
        assertEquals("answer", begin(chat, "key", "hello").answer());
        assertThrows(ResponseStatusException.class, () -> begin(chat, "key", "different"));
        assertThrows(ConversationUnavailableException.class, () -> transaction.execute(status -> turns.begin(owner + 1, chat, "other", "hello", false)));
        assertEquals(1, history.countMessages(chat));
    }

    @Test void terminalStateAndAnswerRollbackTogether() {
        String chat = chat(); begin(chat, "key", "hello");
        jdbc.execute("ALTER TABLE psych_chat_message ADD CONSTRAINT no_assistant CHECK(role <> 'assistant')");
        assertThrows(RuntimeException.class, () -> finish(chat, "key", "answer"));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM psych_chat_turn", String.class));
        assertEquals(0, history.countMessages(chat));
    }

    @Test void restartRecoversRunningReservationAndStaleReservationCanRetry() {
        String chat = chat(); begin(chat, "key", "hello");
        new ChatTurnService(jdbc, new ReplayRetentionProperties(), JdbcDialect.H2).initializeSchema();
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM psych_chat_turn", String.class));
        assertFalse(begin(chat, "key", "hello").replay());
        jdbc.update("UPDATE psych_chat_turn SET updated_at = DATEADD('MINUTE', -6, CURRENT_TIMESTAMP)");
        assertFalse(begin(chat, "key", "hello").replay());
    }

    @Test void concurrentAdmissionHasOneWinnerAndDatabaseAlsoEnforcesSingleRunningTurn() throws Exception {
        String chat = chat();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            Callable<Boolean> task = () -> { ready.countDown(); go.await(); try { begin(chat, "key", "hello"); return true; } catch (ResponseStatusException failure) { return false; } };
            var first = pool.submit(task); var second = pool.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO psych_chat_turn(conversation_id,client_msg_id,request_hash,status) VALUES (?, 'other', 'hash', 'RUNNING')", chat));
    }

    @Test void memoryWatermarkNeverMovesBackwardsOrPrunesUncoveredData() {
        String chat = chat();
        transaction.executeWithoutResult(status -> history.appendUserMessage(owner, chat, "one"));
        transaction.executeWithoutResult(status -> history.appendUserMessage(owner, chat, "two"));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM psych_chat_message", Long.class);
        transaction.executeWithoutResult(status -> history.replaceMemoryAndPrune(chat, "new", id, 2, id - 1));
        transaction.executeWithoutResult(status -> history.replaceMemoryAndPrune(chat, "stale", id - 1, 1, id));
        assertEquals("new", history.getDigest(chat));
        assertEquals(1, history.countMessages(chat));
        assertEquals(id, history.getCoveredUntilMessageId(chat));
    }

    @Test void replayAndConversationRetentionAreIndependentAndKeepTombstones() {
        String chat = chat(); begin(chat, "key", "hello"); finish(chat, "key", "answer");
        jdbc.update("UPDATE psych_chat_turn SET updated_at = DATEADD('DAY', -8, CURRENT_TIMESTAMP)");
        turns.expireReplayCache();
        assertEquals(1, history.countMessages(chat));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM psych_chat_turn", Integer.class));
        jdbc.update("UPDATE psych_conversation SET updated_at = DATEADD('DAY', -10, CURRENT_TIMESTAMP)");
        var properties = new RetentionProperties(); properties.setConversationDays(7); properties.setApply(true);
        assertEquals(1, new ConversationRetentionService(jdbc, properties, manager, JdbcDialect.H2).runOnce().deleted());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM psych_conversation_tombstone", Integer.class));
    }

    @Test void userDeletionCascadesAndSearchUsesCaseInsensitiveMatching() {
        transaction.executeWithoutResult(status -> history.appendUserMessage(owner, "chat", "Hello WORLD", "key"));
        assertEquals(1, users.listUsers("DESKTOP", "ACTIVE", 0, 20).size());
        assertEquals(1, history.searchRecallCandidates("chat", "world", 10).size());
        assertTrue(Double.isInfinite(history.hoursSincePreviousTurn("chat")));
        transaction.executeWithoutResult(status -> history.appendUserMessage(owner, "chat", "second"));
        assertTrue(Double.isFinite(history.hoursSincePreviousTurn("chat")));
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> users.deleteById(owner))));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM psych_chat_message", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM psych_conversation_tombstone", Integer.class));
    }
}
