package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.history.ChatTurnService;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public final class PipelineTestSupport {
    public static ChatTurnService configure(CounselingTurnPipeline pipeline) {
        ChatTurnService turns = mock(ChatTurnService.class);
        lenient().when(turns.begin(anyLong(), anyString(), any(), anyString(), anyBoolean())).thenAnswer(call ->
                new ChatTurnService.Turn(call.getArgument(2), "RUNNING", "", (boolean) call.getArgument(4) ? "deep" : "standard", false));
        ReflectionTestUtils.setField(pipeline, "chatTurnService", turns);
        ReflectionTestUtils.setField(pipeline, "memoryService", mock(ConversationMemoryService.class));
        return turns;
    }
}
