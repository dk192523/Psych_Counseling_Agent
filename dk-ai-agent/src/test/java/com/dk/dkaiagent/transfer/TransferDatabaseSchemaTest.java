package com.dk.dkaiagent.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Catalog failures are injected here; real PostgreSQL validates the catalog SQL in integration tests. */
class TransferDatabaseSchemaTest {
    @Test
    void acceptsCurrentPostgresWithoutChangingConnectionOrTransaction() throws Exception {
        Fixture fixture = new Fixture();
        TransferDatabaseSchema.validatePostgres(fixture.connection);
        assertFalse(fixture.queries.isEmpty());
        assertTrue(fixture.queries.stream().allMatch(sql -> sql.startsWith("/* transfer-schema:")));
        verify(fixture.connection, never()).setReadOnly(anyBoolean());
        verify(fixture.connection, never()).setAutoCommit(anyBoolean());
        verify(fixture.connection, never()).setTransactionIsolation(anyInt());
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).close();
    }

    @Test
    void rejectsEmbeddedPostgresCompatibilityMode() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.metadata.getDatabaseProductName()).thenReturn("H2");
        assertSafeFailure(fixture.connection);
        assertTrue(fixture.queries.isEmpty());
    }

    @Test
    void rejectsDifferentPostgresMajorVersion() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.metadata.getDatabaseMajorVersion()).thenReturn(17);
        assertSafeFailure(fixture.connection);
        assertTrue(fixture.queries.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "tables", "unexpected-business-relations", "unexpected-columns",
            "column-types-and-nullability", "column-defaults", "primary-keys", "foreign-keys",
            "unexpected-foreign-keys", "unexpected-constraints", "critical-unique-indexes", "unexpected-unique-indexes"})
    void rejectsAnyContractDriftWithoutDisclosingCatalogMetadata(String failedCheck) throws Exception {
        Fixture fixture = new Fixture();
        fixture.failedCheck = failedCheck;
        assertSafeFailure(fixture.connection);
        assertTrue(fixture.queries.getLast().startsWith("/* transfer-schema: " + failedCheck + " */"));
    }

    @Test
    void suppressesJdbcDiagnosticsAndCauses() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.connection.prepareStatement(anyString())).thenThrow(new SQLException("private-schema private-value"));
        assertSafeFailure(fixture.connection);
    }

    @Test
    void incompleteAggregateResultIsNotAcceptedAsZero() throws Exception {
        Fixture fixture = new Fixture();
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(fixture.connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        assertSafeFailure(fixture.connection);
        verify(rows).close();
        verify(statement).close();
    }

    @Test
    void tableContractIncludesUnsafeReadAndWriteMechanisms() {
        String tables = TransferDatabaseSchema.contractQueries().stream()
                .filter(sql -> sql.startsWith("/* transfer-schema: tables */")).findFirst().orElseThrow();
        for (String token : List.of("relkind<>'r'", "relispartition", "pg_inherits", "pg_trigger",
                "NOT x.tgisinternal", "relrowsecurity", "relforcerowsecurity", "pg_policy", "pg_rewrite")) {
            assertTrue(tables.contains(token), token);
        }
    }

    private static void assertSafeFailure(Connection connection) {
        SQLException error = assertThrows(SQLException.class, () -> TransferDatabaseSchema.validatePostgres(connection));
        assertEquals(TransferDatabaseSchema.VALIDATION_ERROR, error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static final class Fixture {
        private final Connection connection = mock(Connection.class);
        private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        private final List<String> queries = new ArrayList<>();
        private String failedCheck;

        private Fixture() throws SQLException {
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(metadata.getDatabaseMajorVersion()).thenReturn(16);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                queries.add(sql);
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet result = mock(ResultSet.class);
                when(statement.executeQuery()).thenReturn(result);
                when(result.next()).thenReturn(true, false);
                when(result.getLong(1)).thenReturn(failedCheck != null
                        && sql.startsWith("/* transfer-schema: " + failedCheck + " */") ? 1L : 0L);
                return statement;
            });
        }
    }
}
