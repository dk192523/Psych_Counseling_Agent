package com.dk.dkaiagent.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationRetentionServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private PlatformTransactionManager transactionManager;

    private RetentionProperties properties;
    private ConversationRetentionService service;

    @BeforeEach
    void setUp() {
        properties = new RetentionProperties();
        service = new ConversationRetentionService(jdbc, properties, transactionManager);
    }

    @Test
    void unconfiguredDefaultsDoNotAccessDatabaseOrStartTransactions() {
        ConversationRetentionService.BatchResult result = service.runOnce();

        assertFalse(result.apply());
        assertFalse(result.configured());
        assertEquals(0, result.candidates());
        assertEquals(0, result.deleted());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        assertTrue(result.idSample().isEmpty());
        verifyNoInteractions(jdbc, transactionManager);
    }

    @Test
    void applyWithoutRetentionDaysFailsBeforeAccessingDatabase() {
        properties.setApply(true);

        assertThrows(IllegalArgumentException.class, service::runOnce);

        verifyNoInteractions(jdbc, transactionManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredDryRunCountsCandidatesWithoutWritesOrTransactions() {
        properties.setConversationDays(30);
        Timestamp cutoff = Timestamp.from(Instant.parse("2026-08-01T00:00:00Z"));
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class), eq(30)))
                .thenReturn(cutoff);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(cutoff), eq(100)))
                .thenReturn(List.of("synthetic-conversation-a", "synthetic-conversation-b"));

        ConversationRetentionService.BatchResult result = service.runOnce();

        assertFalse(result.apply());
        assertTrue(result.configured());
        assertEquals(2, result.candidates());
        assertEquals(0, result.deleted());
        assertEquals(0, result.skipped());
        assertEquals(0, result.failed());
        verify(jdbc).queryForObject(anyString(), eq(Timestamp.class), eq(30));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(cutoff), eq(100));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(transactionManager);
    }
}
