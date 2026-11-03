package com.dk.dkaiagent.transfer;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.gt;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferExporterTest {
    private static final String NAMESPACE = "20993a7a-14c4-4ea1-bbe5-57fdac6a8671";
    private static final Instant TIME = Instant.parse("2026-10-09T08:23:45.123456Z");

    @Test
    void exportsOnlyBusinessFieldsPreservesWhitespaceAndAcceptsPrunedWatermarkAndDeletedOwner() throws Exception {
        Fixture fixture = new Fixture();
        TransferSnapshot snapshot = TransferExporter.export(fixture.connection, NAMESPACE);
        assertEquals(List.of(new TransferSnapshot.User(7L, "student")), snapshot.users());
        assertEquals("  我想聊一聊\t\r\n第二行  ", snapshot.messages().getFirst().content());
        assertNull(snapshot.messages().getFirst().clientMsgId());
        assertEquals(TIME.toString(), snapshot.conversations().getFirst().createdAt());
        assertEquals(TIME.toString(), snapshot.conversations().getFirst().updatedAt());
        assertEquals(TIME.toString(), snapshot.messages().getFirst().createdAt());
        assertEquals(TIME.toString(), snapshot.memories().getFirst().updatedAt());
        assertEquals(TIME.toString(), snapshot.turns().getFirst().updatedAt());
        assertEquals(TIME.toString(), snapshot.tombstones().getFirst().deletedAt());
        assertEquals(9999L, snapshot.memories().getFirst().coveredUntilMessageId());
        assertEquals(99L, snapshot.tombstones().getFirst().ownerId());
        assertEquals("RUNNING", snapshot.turns().getFirst().status());
        assertEquals(6, fixture.dataQueries.size());
        assertEquals("SELECT id, username FROM public.psych_user ORDER BY id", fixture.dataQueries.getFirst());
        for (String sql : fixture.dataQueries) {
            assertTrue(sql.stripLeading().startsWith("SELECT "));
            assertTrue(sql.contains("ORDER BY"));
            for (String secret : List.of("password_hash", "disabled_reason", "last_login_at", "vector_store")) {
                assertFalse(sql.contains(secret));
            }
        }
        for (PreparedStatement statement : fixture.dataStatements) {
            verify(statement).setFetchSize(gt(0));
            verify(statement).setQueryTimeout(30);
            verify(statement, never()).executeUpdate();
            verify(statement).close();
        }
        assertTrue(fixture.cursors.stream().allMatch(cursor -> cursor.closed));
        assertLifecycle(fixture.connection);
    }

    @Test
    void refusesExistingCallerTransactionWithoutRollingItBack() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.connection.getAutoCommit()).thenReturn(false);
        assertSafeExportFailure(fixture.connection);
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).setReadOnly(anyBoolean());
        verify(fixture.connection, never()).setAutoCommit(anyBoolean());
        verify(fixture.connection, never()).setTransactionIsolation(anyInt());
        assertTrue(fixture.dataQueries.isEmpty());
    }

    @Test
    void checksActualServerReadOnlyStateBeforeReadingTheSchemaOrBusinessData() throws Exception {
        Fixture fixture = new Fixture();
        fixture.serverReadOnly = false;
        assertSafeExportFailure(fixture.connection);
        assertEquals(1, fixture.catalogQueries.size());
        assertTrue(fixture.dataQueries.isEmpty());
        assertLifecycle(fixture.connection);
    }

    @Test
    void catalogMismatchBlocksBusinessReadsAndRestoresConnection() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failedCheck = "foreign-keys";
        assertSafeExportFailure(fixture.connection);
        assertTrue(fixture.dataQueries.isEmpty());
        assertLifecycle(fixture.connection);
    }

    @Test
    void sqlFailureDiscardsPrivateDiagnosticsAndRestoresConnection() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failedTable = "psych_chat_message";
        assertSafeExportFailure(fixture.connection);
        assertLifecycle(fixture.connection);
        assertTrue(fixture.cursors.stream().allMatch(cursor -> cursor.closed));
    }

    @Test
    void nullLiveOwnerBlocksRatherThanAdoptingConversation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.rows.get("psych_conversation").getFirst().put("owner_id", null);
        TransferException error = assertThrows(TransferException.class,
                () -> TransferExporter.export(fixture.connection, NAMESPACE));
        assertEquals(TransferException.Code.INVALID_VALUE, error.code());
        assertEquals(2, fixture.dataQueries.size());
        assertLifecycle(fixture.connection);
    }

    @Test
    void malformedNamespaceBlocksBeforeChangingConnectionState() throws Exception {
        Fixture fixture = new Fixture();
        TransferException error = assertThrows(TransferException.class,
                () -> TransferExporter.export(fixture.connection, "private invalid namespace"));
        assertEquals(TransferException.Code.INVALID_VALUE, error.code());
        verify(fixture.connection, never()).setReadOnly(anyBoolean());
        verify(fixture.connection, never()).rollback();
    }

    @Test
    void oversizedUtf8TextIsRejectedDuringReading() throws Exception {
        Fixture fixture = new Fixture();
        fixture.rows.get("psych_chat_message").getFirst().put("content", "好".repeat(TransferFormat.MAX_TEXT_BYTES / 3 + 1));
        TransferException error = assertThrows(TransferException.class,
                () -> TransferExporter.export(fixture.connection, NAMESPACE));
        assertEquals(TransferException.Code.LIMIT_EXCEEDED, error.code());
        assertEquals(3, fixture.dataQueries.size());
        assertLifecycle(fixture.connection);
    }

    @Test
    void recordLimitStopsBeforeMaterializingTheExtraRow() throws Exception {
        Fixture fixture = new Fixture();
        fixture.rows.put("psych_user", Collections.nCopies(TransferFormat.MAX_RECORDS + 1,
                row("id", 7L, "username", "student")));
        TransferException error = assertThrows(TransferException.class,
                () -> TransferExporter.export(fixture.connection, NAMESPACE));
        assertEquals(TransferException.Code.LIMIT_EXCEEDED, error.code());
        assertEquals(1, fixture.dataQueries.size());
        assertEquals(TransferFormat.MAX_RECORDS, fixture.cursors.getFirst().textReads);
        assertLifecycle(fixture.connection);
    }

    @Test
    void preservesOriginalReadOnlySetting() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.connection.isReadOnly()).thenReturn(true);
        TransferExporter.export(fixture.connection, NAMESPACE);
        verify(fixture.connection, times(2)).setReadOnly(true);
        verify(fixture.connection, never()).setReadOnly(false);
        verify(fixture.connection).rollback();
    }

    @Test
    void rollbackFailureClosesConnectionAndNeverEnablesImplicitCommit() throws Exception {
        Fixture fixture = new Fixture();
        doThrow(new SQLException("private rollback failure")).when(fixture.connection).rollback();
        assertSafeExportFailure(fixture.connection);
        verify(fixture.connection).close();
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.connection, never()).commit();
    }

    @Test
    void restorationFailureClosesConnectionAndSuppressesDiagnostics() throws Exception {
        Fixture fixture = new Fixture();
        doThrow(new SQLException("private restore failure")).when(fixture.connection).setReadOnly(false);
        assertSafeExportFailure(fixture.connection);
        verify(fixture.connection).rollback();
        verify(fixture.connection).close();
        verify(fixture.connection, never()).commit();
    }

    private static void assertSafeExportFailure(Connection connection) {
        SQLException error = assertThrows(SQLException.class, () -> TransferExporter.export(connection, NAMESPACE));
        assertEquals(TransferExporter.EXPORT_ERROR, error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static void assertLifecycle(Connection connection) throws SQLException {
        InOrder order = inOrder(connection);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).rollback();
        order.verify(connection).setAutoCommit(true);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(connection).setReadOnly(false);
        verify(connection, never()).commit();
        verify(connection, never()).close();
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
        return row;
    }

    private static final class Fixture {
        private final Connection connection = mock(Connection.class);
        private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        private final List<String> catalogQueries = new ArrayList<>();
        private final List<String> dataQueries = new ArrayList<>();
        private final List<PreparedStatement> dataStatements = new ArrayList<>();
        private final List<Cursor> cursors = new ArrayList<>();
        private String failedTable;
        private String failedCheck;
        private boolean serverReadOnly = true;

        private Fixture() throws SQLException {
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(metadata.getDatabaseMajorVersion()).thenReturn(16);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                catalogQueries.add(sql);
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet result = mock(ResultSet.class);
                when(statement.executeQuery()).thenReturn(result);
                when(result.next()).thenReturn(true, false);
                when(result.getBoolean(1)).thenAnswer(ignored -> serverReadOnly);
                when(result.getLong(1)).thenReturn(failedCheck != null
                        && sql.startsWith("/* transfer-schema: " + failedCheck + " */") ? 1L : 0L);
                return statement;
            });
            when(connection.prepareStatement(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                    .thenAnswer(invocation -> {
                        String sql = invocation.getArgument(0);
                        dataQueries.add(sql);
                        String table = rows.keySet().stream().filter(name -> sql.contains("FROM public." + name + " "))
                                .findFirst().orElseThrow();
                        if (table.equals(failedTable)) throw new SQLException("private content, URL and credentials");
                        PreparedStatement statement = mock(PreparedStatement.class);
                        dataStatements.add(statement);
                        Cursor cursor = new Cursor(rows.get(table));
                        cursors.add(cursor);
                        when(statement.executeQuery()).thenReturn(cursor.resultSet());
                        return statement;
                    });
            Timestamp timestamp = Timestamp.from(TIME);
            rows.put("psych_user", List.of(row("id", 7L, "username", "student")));
            rows.put("psych_conversation", List.of(row("id", "chat", "title", "标题", "owner_id", 7L,
                    "created_at", timestamp, "updated_at", timestamp)));
            rows.put("psych_chat_message", List.of(row("id", 21L, "conversation_id", "chat", "role", "user",
                    "content", "  我想聊一聊\t\r\n第二行  ", "client_msg_id", null, "created_at", timestamp)));
            rows.put("psych_conversation_memory", List.of(row("conversation_id", "chat", "digest", "摘要\n",
                    "covered_until_message_id", 9999L, "covered_message_count", 1, "digest_chars", 3, "updated_at", timestamp)));
            rows.put("psych_chat_turn", List.of(row("conversation_id", "chat", "client_msg_id", "key",
                    "request_hash", "a".repeat(64), "status", "RUNNING", "answer", "", "effective_mode", "standard",
                    "fallback", false, "updated_at", timestamp)));
            rows.put("psych_conversation_tombstone", List.of(row("conversation_id", "deleted", "owner_id", 99L,
                    "deleted_at", timestamp)));
        }
    }

    /** A small JDBC cursor fake avoids retaining 100,000 Mockito invocations in the limit test. */
    private static final class Cursor {
        private final List<Map<String, Object>> rows;
        private int index = -1;
        private boolean wasNull;
        private boolean closed;
        private int textReads;

        private Cursor(List<Map<String, Object>> rows) { this.rows = rows; }

        private ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "next": return ++index < rows.size();
                            case "close": closed = true; return null;
                            case "isClosed": return closed;
                            case "wasNull": return wasNull;
                            case "toString": return "test cursor";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: break;
                        }
                        Object value = rows.get(index).get((String) args[0]);
                        wasNull = value == null;
                        return switch (method.getName()) {
                            case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                            case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                            case "getBoolean" -> value != null && (Boolean) value;
                            case "getCharacterStream" -> {
                                textReads++;
                                yield value == null ? null : new StringReader((String) value);
                            }
                            case "getTimestamp" -> {
                                assertEquals("UTC", ((Calendar) args[1]).getTimeZone().getID());
                                yield value;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }
}
