package com.dk.dkaiagent.app;

import com.dk.dkaiagent.history.ConversationHistoryService;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import com.dk.dkaiagent.rag.QueryRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CounselingAppAdmissionTest {
    @Test void emptyKnowledgeDoesNotRegisterRetrievalAdvisorsOrTouchVectorStore() {
        ChatModel model = mock(ChatModel.class);
        VectorStore store = mock(VectorStore.class);
        CounselingApp app = new CounselingApp(model, mock(ConversationHistoryService.class), mock(ConversationMemoryService.class), 30);
        ReflectionTestUtils.setField(app, "pgVectorVectorStore", store);
        List<?> advisors = ReflectionTestUtils.invokeMethod(app, "ragAdvisors", "我和室友吵架了");
        assertTrue(advisors.isEmpty());
        verifyNoInteractions(store);
    }

    @Test void emptyKnowledgeExplicitQuestionsCompleteWithoutCallingAnyModelOrRewriter() {
        ChatModel model = mock(ChatModel.class);
        VectorStore store = mock(VectorStore.class);
        QueryRewriter rewriter = mock(QueryRewriter.class);
        CounselingApp app = new CounselingApp(model, mock(ConversationHistoryService.class), mock(ConversationMemoryService.class), 30);
        ReflectionTestUtils.setField(app, "pgVectorVectorStore", store);
        ReflectionTestUtils.setField(app, "queryRewriter", rewriter);
        var chunks = app.doChatWithRagByStreamPrepared(42L, "CBT 是什么", "chat").collectList().block();
        assertEquals(List.of(com.dk.dkaiagent.rag.KnowledgeAdmissionPolicy.INSUFFICIENT_MESSAGE), chunks);
        verify(model, never()).stream(any(Prompt.class));
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(store, rewriter);
    }
}
