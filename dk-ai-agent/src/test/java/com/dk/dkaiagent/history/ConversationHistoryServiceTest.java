package com.dk.dkaiagent.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTest {

    private static final long OWNER_ID = 42L;
    private static final long OTHER_OWNER_ID = 43L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ConversationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationHistoryService(jdbcTemplate, 3, 5);
    }

    @Test
    void initializesTablesAndIndexes() {
        service.initializeSchema();

        // 3 张业务表 + 墓碑表 + 2 个普通索引 + client_msg_id 列（ALTER）+ 幂等唯一索引 = 8 条 DDL。
        verify(jdbcTemplate, times(8)).execute(anyString());
        verify(jdbcTemplate).execute(argThat((String sql) -> sql != null
                && sql.contains("psych_conversation_memory")
                && sql.contains("covered_message_count")
                && sql.contains("ON DELETE CASCADE")));
        // 删除墓碑表：主键 conversation_id，刻意不带 psych_conversation 的 FK。
        verify(jdbcTemplate).execute(argThat((String sql) -> sql != null
                && sql.contains("CREATE TABLE IF NOT EXISTS psych_conversation_tombstone")
                && sql.contains("conversation_id VARCHAR(64) PRIMARY KEY")
                && sql.contains("owner_id BIGINT NOT NULL")));
        // 幂等键：可重复执行的 ADD COLUMN + (conversation_id, client_msg_id) 唯一索引。
        verify(jdbcTemplate).execute(argThat((String sql) -> sql != null
                && sql.contains("ADD COLUMN IF NOT EXISTS client_msg_id")));
        verify(jdbcTemplate).execute(argThat((String sql) -> sql != null
                && sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_psych_chat_message_client_msg")
                && sql.contains("ON psych_chat_message (conversation_id, client_msg_id)")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createConversationWritesOwnerIdAndFiltersSummaryByOwner() {
        Instant now = Instant.parse("2026-07-22T08:00:00Z");
        ConversationSummary summary = new ConversationSummary("chat-id", "新会话", now, now, 0, null, 3);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("WHERE id = ? AND owner_id = ?")),
                any(RowMapper.class),
                eq(3),
                anyString(),
                eq(OWNER_ID)))
                .thenReturn(List.of(summary));

        ConversationSummary created = service.createConversation(OWNER_ID);

        assertEquals("chat-id", created.id());
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation")
                        && sql.contains("owner_id")),
                anyString(),
                eq("新会话"),
                eq(OWNER_ID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstUserMessageCreatesTitlePreservesContentAndSkipsHardPrune() {
        String content = "  一二三四五六\n第二行  ";
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT owner_id")),
                any(RowMapper.class),
                eq("chat-id")))
                .thenReturn(List.of(OWNER_ID));

        service.appendUserMessage(OWNER_ID, " chat-id ", content);

        // bootstrap 条件插入：墓碑 NOT EXISTS 守卫使"并发删除后的复活"原子地不落行，
        // 参数须含两次 chatId（插入值 + 墓碑子查询）。
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation")
                        && sql.contains("owner_id")
                        && sql.contains("psych_conversation_tombstone")
                        && sql.contains("ON CONFLICT (id) DO NOTHING")),
                eq("chat-id"),
                eq("一二三四五…"),
                eq(OWNER_ID),
                eq("chat-id"));
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                eq("chat-id"),
                eq("user"),
                eq(content),
                isNull()
        );
        // 原文档案只追加：追加路径不再有滑动窗口硬删除，淘汰交给 replaceMemoryAndPrune。
        verify(jdbcTemplate, times(4)).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_chat_message")),
                (Object[]) any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientMsgIdRetryIsDeduplicatedByIdempotentInsert() {
        // 带幂等键的用户消息：INSERT 携带 client_msg_id 与 ON CONFLICT DO NOTHING。
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT owner_id")),
                any(RowMapper.class),
                eq("chat-id")))
                .thenReturn(List.of(OWNER_ID));
        // lenient：同一路径还会发起 bootstrap/title/updated_at 三次 update，均无桩，
        // 严格桩模式下不希望它们触发 PotentialStubbingProblem。
        lenient().when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                any(Object[].class)))
                .thenReturn(1);

        assertTrue(service.appendUserMessage(OWNER_ID, "chat-id", "你好", "client-msg-1"));

        verify(jdbcTemplate).update(
                argThat(sql -> sql != null
                        && sql.contains("client_msg_id")
                        && sql.contains("ON CONFLICT (conversation_id, client_msg_id) DO NOTHING")),
                eq("chat-id"),
                eq("user"),
                eq("你好"),
                eq("client-msg-1"));

        // SSE 中断后重发同一 clientMsgId：唯一索引令 INSERT 原子地不落行（update 返回 0），
        // 服务层折算为 false，调用方据此知道这是一次重放而非新消息。
        lenient().when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                any(Object[].class)))
                .thenReturn(0);

        assertFalse(service.appendUserMessage(OWNER_ID, "chat-id", "你好", "client-msg-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendUserMessageRejectsConversationOwnedBySomeoneElse() {
        // 会话已存在但归属他人：upsert DO NOTHING 不落行，归属守卫抛异常由控制器转 404，
        // 且不写入任何消息。
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT owner_id")),
                any(RowMapper.class),
                eq("chat-id")))
                .thenReturn(List.of(OTHER_OWNER_ID));

        assertThrows(IllegalStateException.class,
                () -> service.appendUserMessage(OWNER_ID, "chat-id", "你好"));

        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                (Object[]) any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendUserMessageRejectsWhenTombstoneCommittedDuringConflictWait() {
        // 零窗口墓碑复核：条件插入的 NOT EXISTS 快照过期（并发删除者在冲突等待期间提交墓碑）后，
        // 紧随插入的复核语句以新快照见墓碑并抛异常——@Transactional 回滚投机插入，已删会话不复活。
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("psych_conversation_tombstone")),
                eq(Integer.class),
                eq("chat-id")))
                .thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.appendUserMessage(OWNER_ID, "chat-id", "你好"));

        // 复核先于归属守卫与消息写入：不得再查 owner、不得写消息。
        verify(jdbcTemplate, never()).query(
                argThat(sql -> sql != null && sql.contains("SELECT owner_id")),
                any(RowMapper.class),
                anyString());
        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                (Object[]) any());
    }

    @Test
    void assistantAppendIsConditionalOnConversationStillExisting() {
        // 会话在流式回答期间被删（级联删除先提交）：EXISTS 守卫使插入原子地不落任何行。
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                any(), any(), any(), any()))
                .thenReturn(0);

        int inserted = service.appendAssistantMessage(OWNER_ID, "chat-id", "迟到的回答");

        assertEquals(0, inserted);
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")
                        && sql.contains("WHERE EXISTS (SELECT 1 FROM psych_conversation WHERE id = ?)")),
                eq("chat-id"),
                eq("assistant"),
                eq("迟到的回答"),
                eq("chat-id"));
        // 助手路径绝不 upsert 父会话行：已删会话不得以"新会话"复活。
        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation")),
                (Object[]) any());
    }

    @Test
    void assistantAppendReturnsOneWhenConversationExists() {
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                any(), any(), any(), any()))
                .thenReturn(1);

        assertEquals(1, service.appendAssistantMessage(OWNER_ID, "chat-id", "回答"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assistantAppendRejectsConversationOwnedBySomeoneElse() {
        // 会话存在但归属他人：归属守卫抛异常，杜绝流式写入跨用户会话的幽灵路径。
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT owner_id")),
                any(RowMapper.class),
                eq("chat-id")))
                .thenReturn(List.of(OTHER_OWNER_ID));

        assertThrows(IllegalStateException.class,
                () -> service.appendAssistantMessage(OWNER_ID, "chat-id", "迟到的回答"));

        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                (Object[]) any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentMessagesAreLimitedAndReturnedInChronologicalOrder() {
        List<Message> newestFirst = List.of(
                new AssistantMessage("new answer"),
                new UserMessage("older question")
        );
        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq("chat-id"),
                eq(3)
        )).thenReturn(newestFirst);

        List<Message> result = service.getRecentMessages("chat-id", 99);

        assertEquals(2, result.size());
        assertInstanceOf(UserMessage.class, result.get(0));
        assertEquals("older question", result.get(0).getText());
        assertInstanceOf(AssistantMessage.class, result.get(1));
        assertEquals("new answer", result.get(1).getText());
    }

    @Test
    void nonPositiveContextLimitReturnsEmptyWithoutQuery() {
        assertEquals(List.of(), service.getRecentMessages("chat-id", 0));
    }

    @Test
    void blankMessageIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.appendUserMessage(OWNER_ID, "chat-id", "  \n"));
    }

    @Test
    void deleteFiltersByOwnerAndReturnsWhetherConversationExisted() {
        when(jdbcTemplate.update(anyString(), eq("chat-id"), eq(OWNER_ID))).thenReturn(1);

        assertEquals(true, service.delete("chat-id", OWNER_ID));
        // owner 过滤：跨用户删除与"不存在"同形（返回 false），控制器统一 404。
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_conversation")
                        && sql.contains("id = ? AND owner_id = ?")),
                eq("chat-id"),
                eq(OWNER_ID));
        // 同事务补墓碑：并发在途流的 bootstrap 对已删 id 永久失效。
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null
                        && sql.contains("INSERT INTO psych_conversation_tombstone")
                        && sql.contains("ON CONFLICT (conversation_id) DO UPDATE SET deleted_at = CURRENT_TIMESTAMP")),
                eq("chat-id"),
                eq(OWNER_ID));
    }

    @Test
    void deleteMissWritesNoTombstone() {
        // owner 过滤未命中（跨用户或不存在）：DELETE 0 行 → 不写墓碑、返回 false。
        when(jdbcTemplate.update(anyString(), eq("a-chat"), eq(OWNER_ID))).thenReturn(0);

        assertEquals(false, service.delete("a-chat", OWNER_ID));
        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation_tombstone")),
                (Object[]) any());
    }

    @Test
    void replaceMemoryAndPruneUpsertsMemoryRowBeforePruningRawMessages() {
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation_memory")),
                any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.replaceMemoryAndPrune("chat-id", "摘要内容", 42L, 12, 30L);

        InOrder ordered = inOrder(jdbcTemplate);
        ordered.verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation_memory")
                        && sql.contains("ON CONFLICT (conversation_id)")
                        && sql.contains("digest_chars")
                        && sql.contains("covered_until_message_id < EXCLUDED.covered_until_message_id")),
                eq("chat-id"),
                eq("摘要内容"),
                eq(42L),
                eq(12),
                eq("摘要内容".length())
        );
        ordered.verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_chat_message") && sql.contains("id <= ?")),
                eq("chat-id"),
                eq(30L)
        );
    }

    @Test
    void replaceMemoryAndPruneSkipsPruneWhenWatermarkDoesNotAdvance() {
        // upsert 的 CAS WHERE 不满足时（另一个 JVM 已把水位推得更远）语句返回 0 行：
        // 过期写者不得剪枝，否则胜者已删除而该写者 digest 未覆盖的消息将永久丢失。
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation_memory")),
                any(), any(), any(), any(), any()))
                .thenReturn(0);

        service.replaceMemoryAndPrune("chat-id", "过期摘要", 60L, 6, 60L);

        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_chat_message")),
                (Object[]) any());
    }

    @Test
    void replaceMemoryAndPruneDoesNotPruneWhenUpsertFails() {
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("INSERT INTO psych_conversation_memory")),
                any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("constraint violation"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.replaceMemoryAndPrune("chat-id", "digest", 1L, 1, 1L));

        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_chat_message")),
                (Object[]) any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncoveredMessagesStartAfterCoveredWatermark() {
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("covered_until_message_id")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of(7L));
        List<ConversationMessage> page = List.of(
                new ConversationMessage(8L, "user", "new message", Instant.parse("2026-07-22T09:00:00Z")));
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("id > ?")),
                any(RowMapper.class),
                eq("chat-id"),
                eq(7L),
                eq(10)
        )).thenReturn(page);

        assertEquals(page, service.getUncoveredMessages("chat-id", 10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncoveredMessagesIncludeEverythingWhenNoMemoryRow() {
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("covered_until_message_id")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("id > ?")),
                any(RowMapper.class),
                eq("chat-id"),
                eq(0L),
                eq(5)
        )).thenReturn(List.of());

        assertEquals(List.of(), service.getUncoveredMessages("chat-id", 5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oldestMessagesAreRequestedInAscendingOrder() {
        List<ConversationMessage> oldest = List.of(
                new ConversationMessage(1L, "user", "first", Instant.parse("2026-07-22T08:00:00Z")));
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("ORDER BY id ASC") && !sql.contains("id > ?")),
                any(RowMapper.class),
                eq("chat-id"),
                eq(6)
        )).thenReturn(oldest);

        assertEquals(oldest, service.getOldestMessages("chat-id", 6));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchRecallCandidatesPrefersKeywordHitsThenRecency() {
        List<ConversationMessage> candidates = List.of(
                new ConversationMessage(5L, "user", "失眠话题", Instant.parse("2026-07-22T08:00:00Z")));
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("ORDER BY (content ILIKE ?) DESC, id DESC")),
                any(RowMapper.class),
                eq("chat-id"),
                eq("%失眠%"),
                eq(30)
        )).thenReturn(candidates);

        assertEquals(candidates, service.searchRecallCandidates("chat-id", "失眠", 30));
    }

    @Test
    void nonPositiveMemoryReadLimitsSkipQueries() {
        assertEquals(List.of(), service.getUncoveredMessages("chat-id", 0));
        assertEquals(List.of(), service.getOldestMessages("chat-id", -1));
        assertEquals(List.of(), service.searchRecallCandidates("chat-id", "关键词", 0));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDigestFallsBackToEmptyWhenNoMemoryRow() {
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("FROM psych_conversation_memory")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of());

        assertEquals("", service.getDigest("chat-id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMemoryStatsMapsMemoryRow() {
        Instant updatedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)")),
                eq(Long.class),
                eq("chat-id")
        )).thenReturn(9L);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("FROM psych_conversation_memory")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of(new ConversationHistoryService.MemoryRow("长期摘要", 6, 12, updatedAt)));

        MemoryStats stats = service.getMemoryStats("chat-id");

        assertEquals(9, stats.messageCount());
        assertEquals(3, stats.maxMessages());
        assertEquals(6, stats.digestedCount());
        assertEquals(12, stats.digestChars());
        assertEquals("长期摘要", stats.digest());
        assertEquals(updatedAt, stats.updatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getMemoryStatsZerosWhenNoMemoryRow() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql != null && sql.contains("COUNT(*)")),
                eq(Long.class),
                eq("chat-id")
        )).thenReturn(0L);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("FROM psych_conversation_memory")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of());

        MemoryStats stats = service.getMemoryStats("chat-id");

        assertEquals(0, stats.messageCount());
        assertEquals(0, stats.digestedCount());
        assertEquals(0, stats.digestChars());
        assertEquals("", stats.digest());
        assertNull(stats.updatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listConversationsExposesMaxMessagesAndFiltersByOwner() {
        Instant now = Instant.parse("2026-07-22T08:00:00Z");
        ConversationSummary summary = new ConversationSummary("chat-id", "title", now, now, 2, "preview", 3);
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("max_messages")
                        && sql.contains("WHERE c.owner_id = ?")),
                any(RowMapper.class),
                eq(3),
                eq(OWNER_ID),
                eq(50)
        )).thenReturn(List.of(summary));

        List<ConversationSummary> result = service.listConversations(OWNER_ID);

        assertEquals(3, result.getFirst().maxMessages());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getConversationAttachesMemoryAndMaxMessages() {
        Instant now = Instant.parse("2026-07-22T08:00:00Z");
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("GROUP BY c.id")
                        && sql.contains("WHERE c.id = ? AND c.owner_id = ?")),
                any(RowMapper.class),
                eq(3),
                eq("chat-id"),
                eq(OWNER_ID)
        )).thenReturn(List.of(new ConversationSummary("chat-id", "title", now, now, 2, "preview", 3)));
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("SELECT id, role, content, created_at")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of(new ConversationMessage(1L, "user", "hi", now)));
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("FROM psych_conversation_memory")),
                any(RowMapper.class),
                eq("chat-id")
        )).thenReturn(List.of(new ConversationHistoryService.MemoryRow("摘要", 1, 6, now)));

        ConversationDetail detail = service.getConversation("chat-id", OWNER_ID).orElseThrow();

        assertEquals(3, detail.memory().maxMessages());
        assertEquals(2, detail.memory().messageCount());
        assertEquals(1, detail.memory().digestedCount());
        assertEquals(6, detail.memory().digestChars());
        assertEquals("摘要", detail.memory().digest());
        assertEquals(now, detail.memory().updatedAt());
        assertEquals(1, detail.messages().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getConversationReturnsEmptyForUnknownId() {
        when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("GROUP BY c.id")),
                any(RowMapper.class),
                eq(3),
                eq("missing"),
                eq(OWNER_ID)
        )).thenReturn(List.of());

        assertEquals(Optional.empty(), service.getConversation("missing", OWNER_ID));
    }

    @Test
    void clearFiltersEveryStatementByOwner() {
        service.clear("chat-id", OWNER_ID);

        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_chat_message")
                        && sql.contains("owner_id = ?")),
                eq("chat-id"),
                eq(OWNER_ID));
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM psych_conversation_memory")
                        && sql.contains("owner_id = ?")),
                eq("chat-id"),
                eq(OWNER_ID));
        verify(jdbcTemplate).update(
                argThat(sql -> sql != null && sql.contains("UPDATE psych_conversation")
                        && sql.contains("id = ? AND owner_id = ?")),
                eq("新会话"),
                eq("chat-id"),
                eq(OWNER_ID));
    }
}
