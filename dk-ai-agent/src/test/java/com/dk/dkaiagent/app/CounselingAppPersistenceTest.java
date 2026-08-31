package com.dk.dkaiagent.app;

import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.history.ConversationUnavailableException;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CounselingAppPersistenceTest {

    private final ConversationHistoryService historyService = mock(ConversationHistoryService.class);
    private final CounselingApp app = new CounselingApp(
            mock(ChatModel.class), historyService, mock(ConversationMemoryService.class), 30);

    @Test
    void persistenceFailurePropagatesInsteadOfCompletingTheAnswerStream() {
        when(historyService.appendAssistantMessage(42L, "chat-id", "answer"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        app, "persistAssistantMessage", 42L, "chat-id", "answer"));
    }

    @Test
    void concurrentlyDeletedConversationMayEndWithoutResurrectingIt() {
        when(historyService.appendAssistantMessage(42L, "chat-id", "answer"))
                .thenThrow(new ConversationUnavailableException("deleted"));

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                app, "persistAssistantMessage", 42L, "chat-id", "answer"));
    }
}
