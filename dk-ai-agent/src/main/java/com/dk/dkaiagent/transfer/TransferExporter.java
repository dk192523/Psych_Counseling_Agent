package com.dk.dkaiagent.transfer;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static com.dk.dkaiagent.transfer.TransferException.Code.INVALID_VALUE;
import static com.dk.dkaiagent.transfer.TransferException.Code.LIMIT_EXCEEDED;

/** Explicit JDBC reads only: no Spring context, schema initialization or account secrets. */
public final class TransferExporter {
    public static final String EXPORT_ERROR = "PostgreSQL business export failed.";
    private static final int FETCH_SIZE = 128;

    private TransferExporter() { }

    /**
     * Caller owns a fresh autocommit connection. The read transaction is always rolled back,
     * and the original autocommit, read-only and isolation settings are restored on either path.
     */
    public static TransferSnapshot export(Connection connection, String sourceNamespace) throws SQLException {
        TransferFormat.validateSnapshot(new TransferSnapshot(TransferSnapshot.FORMAT_VERSION, sourceNamespace,
                TransferDatabaseSchema.SCHEMA_VERSION, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        ConnectionState state;
        try {
            if (connection == null || connection.isClosed() || !connection.getAutoCommit()) throw failed();
            state = new ConnectionState(connection.isReadOnly(), connection.getTransactionIsolation());
        } catch (SQLException | RuntimeException error) {
            throw failed();
        }
        boolean transactionStarted = false;
        try {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            transactionStarted = true;
            verifyReadTransaction(connection);
            TransferDatabaseSchema.validatePostgres(connection);
            Budget budget = new Budget();
            budget.text(sourceNamespace);
            List<TransferSnapshot.User> users = read(connection,
                    "SELECT id, username FROM public.psych_user ORDER BY id", budget,
                    rs -> new TransferSnapshot.User(number(rs, "id"), text(rs, "username", false, budget)));
            List<TransferSnapshot.Conversation> conversations = read(connection, """
                    SELECT id, title, owner_id, created_at, updated_at
                    FROM public.psych_conversation ORDER BY id
                    """, budget, rs -> new TransferSnapshot.Conversation(text(rs, "id", false, budget),
                    text(rs, "title", false, budget), number(rs, "owner_id"),
                    instant(rs, "created_at", budget), instant(rs, "updated_at", budget)));
            List<TransferSnapshot.Message> messages = read(connection, """
                    SELECT id, conversation_id, role, content, client_msg_id, created_at
                    FROM public.psych_chat_message ORDER BY id
                    """, budget, rs -> new TransferSnapshot.Message(number(rs, "id"),
                    text(rs, "conversation_id", false, budget), text(rs, "role", false, budget),
                    text(rs, "content", false, budget), text(rs, "client_msg_id", true, budget),
                    instant(rs, "created_at", budget)));
            List<TransferSnapshot.Memory> memories = read(connection, """
                    SELECT conversation_id, digest, covered_until_message_id,
                        covered_message_count, digest_chars, updated_at
                    FROM public.psych_conversation_memory ORDER BY conversation_id
                    """, budget, rs -> new TransferSnapshot.Memory(text(rs, "conversation_id", false, budget),
                    text(rs, "digest", false, budget), number(rs, "covered_until_message_id"),
                    integer(rs, "covered_message_count"), integer(rs, "digest_chars"),
                    instant(rs, "updated_at", budget)));
            List<TransferSnapshot.Turn> turns = read(connection, """
                    SELECT conversation_id, client_msg_id, request_hash, status, answer,
                        effective_mode, fallback, updated_at
                    FROM public.psych_chat_turn ORDER BY conversation_id, client_msg_id
                    """, budget, rs -> new TransferSnapshot.Turn(text(rs, "conversation_id", false, budget),
                    text(rs, "client_msg_id", false, budget), text(rs, "request_hash", false, budget),
                    text(rs, "status", false, budget), text(rs, "answer", false, budget),
                    text(rs, "effective_mode", false, budget), bool(rs, "fallback"),
                    instant(rs, "updated_at", budget)));
            List<TransferSnapshot.Tombstone> tombstones = read(connection, """
                    SELECT conversation_id, owner_id, deleted_at
                    FROM public.psych_conversation_tombstone ORDER BY conversation_id
                    """, budget, rs -> new TransferSnapshot.Tombstone(text(rs, "conversation_id", false, budget),
                    number(rs, "owner_id"), instant(rs, "deleted_at", budget)));
            TransferSnapshot snapshot = new TransferSnapshot(TransferSnapshot.FORMAT_VERSION, sourceNamespace,
                    TransferDatabaseSchema.SCHEMA_VERSION, users, conversations, messages, memories, turns, tombstones);
            TransferFormat.validateSnapshot(snapshot);
            // Wire size also includes JSON escaping and field names, not just the decoded text budget.
            TransferFormat.writeSnapshot(snapshot);
            return snapshot;
        } catch (TransferException error) {
            throw error;
        } catch (SQLException | RuntimeException error) {
            throw failed();
        } finally {
            restore(connection, state, transactionStarted);
        }
    }

    private static void verifyReadTransaction(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT current_setting('transaction_read_only')='on'
                    AND current_setting('transaction_isolation')='repeatable read'
                """)) {
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1) || rows.wasNull() || rows.next()) throw failed();
            }
        }
    }

    private record ConnectionState(boolean readOnly, int isolation) { }

    private static void restore(Connection connection, ConnectionState state, boolean transactionStarted) throws SQLException {
        boolean failed = false;
        if (transactionStarted) {
            try { connection.rollback(); } catch (SQLException | RuntimeException error) { failed = true; }
            if (failed) {
                // Do not call setAutoCommit(true) after an uncertain rollback: it may commit a transaction.
                try { connection.close(); } catch (SQLException | RuntimeException ignored) { }
                throw failed();
            }
        }
        try { connection.setAutoCommit(true); } catch (SQLException | RuntimeException error) { failed = true; }
        try { connection.setTransactionIsolation(state.isolation()); } catch (SQLException | RuntimeException error) { failed = true; }
        try { connection.setReadOnly(state.readOnly()); } catch (SQLException | RuntimeException error) { failed = true; }
        if (failed) {
            try { connection.close(); } catch (SQLException | RuntimeException ignored) { }
            throw failed();
        }
    }

    private static <T> List<T> read(Connection connection, String sql, Budget budget, RowReader<T> reader) throws SQLException {
        List<T> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            // PostgreSQL uses a cursor only with autocommit off and a positive fetch size.
            statement.setFetchSize(FETCH_SIZE);
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    budget.row();
                    values.add(reader.read(rows));
                }
            }
        }
        return List.copyOf(values);
    }

    @FunctionalInterface
    private interface RowReader<T> { T read(ResultSet rows) throws SQLException; }

    private static long number(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull()) throw new TransferException(INVALID_VALUE);
        return value;
    }

    private static int integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        if (rows.wasNull()) throw new TransferException(INVALID_VALUE);
        return value;
    }

    private static boolean bool(ResultSet rows, String column) throws SQLException {
        boolean value = rows.getBoolean(column);
        if (rows.wasNull()) throw new TransferException(INVALID_VALUE);
        return value;
    }

    private static String instant(ResultSet rows, String column, Budget budget) throws SQLException {
        Timestamp value = rows.getTimestamp(column, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        if (value == null) throw new TransferException(INVALID_VALUE);
        String utc = value.toInstant().toString();
        budget.text(utc);
        return utc;
    }

    private static String text(ResultSet rows, String column, boolean nullable, Budget budget) throws SQLException {
        try (Reader reader = rows.getCharacterStream(column)) {
            if (reader == null) {
                if (nullable) return null;
                throw new TransferException(INVALID_VALUE);
            }
            StringBuilder value = new StringBuilder();
            char[] chunk = new char[4096];
            int count;
            while ((count = reader.read(chunk)) != -1) {
                if ((long) value.length() + count > TransferFormat.MAX_TEXT_BYTES) throw new TransferException(LIMIT_EXCEEDED);
                value.append(chunk, 0, count);
            }
            String result = value.toString();
            budget.text(result);
            return result;
        } catch (IOException error) {
            throw failed();
        }
    }

    private static final class Budget {
        private int records;
        private long bytes;

        private void row() {
            if (++records > TransferFormat.MAX_RECORDS) throw new TransferException(LIMIT_EXCEEDED);
        }

        private void text(String value) {
            int textBytes = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == 0) throw new TransferException(INVALID_VALUE);
                if (c < 0x80) textBytes++;
                else if (c < 0x800) textBytes += 2;
                else if (Character.isHighSurrogate(c)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new TransferException(INVALID_VALUE);
                    textBytes += 4;
                } else if (Character.isLowSurrogate(c)) throw new TransferException(INVALID_VALUE);
                else textBytes += 3;
                if (textBytes > TransferFormat.MAX_TEXT_BYTES) throw new TransferException(LIMIT_EXCEEDED);
            }
            bytes += textBytes;
            if (bytes > TransferFormat.MAX_INPUT_BYTES) throw new TransferException(LIMIT_EXCEEDED);
        }
    }

    private static SQLException failed() { return new SQLException(EXPORT_ERROR, "P0001"); }
}
