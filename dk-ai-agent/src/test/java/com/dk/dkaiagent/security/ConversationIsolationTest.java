package com.dk.dkaiagent.security;

import com.dk.dkaiagent.agent.counseling.CounselingAgentExecutor;
import com.dk.dkaiagent.agent.counseling.CounselingTurnPipeline;
import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.controller.AiController;
import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据隔离核心不变量测试（冻结合约 AUTH-v1）。
 * 跨用户访问一律与"不存在"同形（空 Optional / delete 返回 false / 控制器统一 404），
 * 绝不以 403 泄露会话存在性；写入他人会话被归属守卫拦截。
 *
 * 两个层面：
 *  - 服务层：{@link ConversationHistoryService} 的 SQL 全部携带 owner_id 过滤，且 append 守卫
 *    在归属不符时抛异常、不落任何消息。
 *  - 控制器层：{@link AiController} 以当前主体 id 调下游，他人会话在进入任何下游前即 404。
 */
@ExtendWith(MockitoExtension.class)
class ConversationIsolationTest {

    private static final long USER_A = 1L;
    private static final long USER_B = 2L;
    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    // ---------------------------------------------------------------- 服务层隔离

    @Nested
    class ServiceLayerIsolation {

        @Mock
        private JdbcTemplate jdbcTemplate;

        private ConversationHistoryService service;

        @BeforeEach
        void setUp() {
            service = new ConversationHistoryService(jdbcTemplate, 1000, 30);
        }

        @Test
        @SuppressWarnings("unchecked")
        void getConversationFiltersByOwnerSoForeignChatAppearsAbsent() {
            // 用户 B 读 A 的会话：owner 过滤使查询落空 → 空 Optional（与不存在同形）。
            when(jdbcTemplate.query(
                    argThat((String sql) -> sql != null
                            && sql.contains("WHERE c.id = ? AND c.owner_id = ?")),
                    any(RowMapper.class),
                    eq(1000), eq("a-chat"), eq(USER_B)))
                    .thenReturn(List.of());

            Optional<?> result = service.getConversation("a-chat", USER_B);

            assertTrue(result.isEmpty());
        }

        @Test
        @SuppressWarnings("unchecked")
        void listConversationsOnlyReturnsOwnerRows() {
            ConversationSummary own = new ConversationSummary("b-chat", "title", NOW, NOW, 1, "preview", 1000);
            when(jdbcTemplate.query(
                    argThat((String sql) -> sql != null && sql.contains("WHERE c.owner_id = ?")),
                    any(RowMapper.class),
                    eq(1000), eq(USER_B), eq(50)))
                    .thenReturn(List.of(own));

            List<ConversationSummary> result = service.listConversations(USER_B);

            // B 的列表只含 B 自己的会话：SQL 以 owner_id = ? 过滤，A 的数据结构性不可见。
            assertEquals(1, result.size());
            assertEquals("b-chat", result.get(0).id());
        }

        @Test
        void deleteForeignConversationReturnsFalseLikeMissing() {
            // owner 过滤的 DELETE 命中 0 行 → false；与"不存在"同形，控制器统一 404。
            when(jdbcTemplate.update(anyString(), eq("a-chat"), eq(USER_B))).thenReturn(0);

            boolean deleted = service.delete("a-chat", USER_B);

            assertEquals(false, deleted);
            verify(jdbcTemplate).update(
                    argThat((String sql) -> sql != null
                            && sql.contains("DELETE FROM psych_conversation")
                            && sql.contains("id = ? AND owner_id = ?")),
                    eq("a-chat"), eq(USER_B));
        }

        @Test
        @SuppressWarnings("unchecked")
        void appendUserMessageToForeignConversationIsGuarded() {
            // 会话归属 A，用户 B 追加：守卫抛异常（控制器转 404），绝不写入消息。
            when(jdbcTemplate.query(
                    argThat((String sql) -> sql != null && sql.contains("SELECT owner_id")),
                    any(RowMapper.class),
                    eq("a-chat")))
                    .thenReturn(List.of(USER_A));

            assertThrows(IllegalStateException.class,
                    () -> service.appendUserMessage(USER_B, "a-chat", "你好"));

            verify(jdbcTemplate, never()).update(
                    argThat((String sql) -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                    (Object[]) any());
        }

        @Test
        @SuppressWarnings("unchecked")
        void appendAssistantMessageToForeignConversationIsGuarded() {
            when(jdbcTemplate.query(
                    argThat((String sql) -> sql != null && sql.contains("SELECT owner_id")),
                    any(RowMapper.class),
                    eq("a-chat")))
                    .thenReturn(List.of(USER_A));

            assertThrows(IllegalStateException.class,
                    () -> service.appendAssistantMessage(USER_B, "a-chat", "迟到的回答"));

            verify(jdbcTemplate, never()).update(
                    argThat((String sql) -> sql != null && sql.contains("INSERT INTO psych_chat_message")),
                    (Object[]) any());
        }
    }

    // ---------------------------------------------------------------- 控制器层隔离

    @Nested
    class ControllerLayerIsolation {

        private AiController controller;
        private CounselingApp counselingApp;
        private ConversationHistoryService historyService;
        private CounselingAgentExecutor executor;
        private MockedStatic<CurrentUser> currentUser;

        @BeforeEach
        void setUp() {
            controller = new AiController();
            counselingApp = mock(CounselingApp.class);
            historyService = mock(ConversationHistoryService.class);
            executor = mock(CounselingAgentExecutor.class);
            ReflectionTestUtils.setField(controller, "counselingApp", counselingApp);
            ReflectionTestUtils.setField(controller, "conversationHistoryService", historyService);
            ReflectionTestUtils.setField(controller, "counselingAgentExecutor", executor);
            CounselingTurnPipeline pipeline = new CounselingTurnPipeline();
            ReflectionTestUtils.setField(pipeline, "counselingApp", counselingApp);
            ReflectionTestUtils.setField(pipeline, "counselingAgentExecutor", executor);
            ReflectionTestUtils.setField(controller, "counselingTurnPipeline", pipeline);
            // 当前主体固定为用户 B。
            currentUser = mockStatic(CurrentUser.class);
            currentUser.when(CurrentUser::requireUserId).thenReturn(USER_B);
        }

        @AfterEach
        void releaseMocks() {
            currentUser.close();
        }

        @Test
        void getForeignConversationReturns404() {
            when(historyService.getConversation("a-chat", USER_B)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.getConversation("a-chat"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        void deleteForeignConversationReturns404AndSkipsMemoryClear() {
            when(historyService.delete("a-chat", USER_B)).thenReturn(false);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.deleteConversation("a-chat"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            verify(counselingApp, never()).clearConversationMemory(anyString());
        }

        @Test
        void chatOnForeignConversationReturns404BeforeAnyDownstream() {
            when(historyService.getConversation("a-chat", USER_B)).thenReturn(Optional.empty());

            ResponseStatusException syncEx = assertThrows(ResponseStatusException.class,
                    () -> controller.doChatWithCounselingSync("message", "a-chat"));
            ResponseStatusException sseEx = assertThrows(ResponseStatusException.class,
                    () -> controller.doChatWithCounselingSSE("message", "a-chat", true));

            assertEquals(HttpStatus.NOT_FOUND, syncEx.getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND, sseEx.getStatusCode());
            // 跨用户会话绝不开流/调用模型：下游全部未触达。
            verify(counselingApp, never()).doChatWithRag(anyLong(), anyString(), anyString(), any());
            verify(counselingApp, never()).prepareConversationTurn(anyLong(), anyString(), anyString(), any());
            verify(counselingApp, never()).doChatWithRagByStreamPrepared(anyLong(), anyString(), anyString());
            verify(executor, never()).prepareAndAnswer(anyString(), anyString(), anyLong());
        }

        @Test
        void listConversationsUsesCurrentPrincipalAsOwner() {
            ConversationSummary own = new ConversationSummary("b-chat", "title", NOW, NOW, 0, null, 1000);
            List<ConversationSummary> stubbed = List.of(own);
            when(historyService.listConversations(USER_B)).thenReturn(stubbed);

            List<ConversationSummary> result = controller.listConversations();

            assertSame(stubbed, result);
            // 只以 B 的 id 查询：A 的数据从源头被排除。
            verify(historyService).listConversations(USER_B);
            verify(historyService, never()).listConversations(USER_A);
        }
    }
}
