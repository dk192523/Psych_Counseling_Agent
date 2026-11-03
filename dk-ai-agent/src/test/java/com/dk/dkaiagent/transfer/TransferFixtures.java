package com.dk.dkaiagent.transfer;

import java.util.List;

final class TransferFixtures {
    static final String NAMESPACE = "75b491b9-4082-48af-ad4e-50fcddbc992a";
    static final String TIME = "2026-10-09T12:00:00Z";
    static final String HASH = "a".repeat(64);

    // This fixture is written independently of TransferFormat's serializer.
    static final String JSON = """
            {
              "formatVersion": 1,
              "sourceNamespace": "75b491b9-4082-48af-ad4e-50fcddbc992a",
              "schemaVersion": "psych-business-v1",
              "users": [{"id":7,"username":"source-user"}],
              "conversations": [{"id":"legacy-chat-一","title":"日常沟通","ownerId":7,
                "createdAt":"2026-10-09T12:00:00Z","updatedAt":"2026-10-09T12:00:00Z"}],
              "messages": [{"id":11,"conversationId":"legacy-chat-一","role":"user",
                "content":"  谢谢\\n继续\\t  ","clientMsgId":null,"createdAt":"2026-10-09T12:00:00Z"}],
              "memories": [{"conversationId":"legacy-chat-一","digest":"回顾",
                "coveredUntilMessageId":10,"coveredMessageCount":2,"digestChars":2,
                "updatedAt":"2026-10-09T12:00:00Z"}],
              "turns": [{"conversationId":"legacy-chat-一","clientMsgId":"turn-1",
                "requestHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "status":"INTERRUPTED","answer":"  部分回答\\n ","effectiveMode":"standard",
                "fallback":false,"updatedAt":"2026-10-09T12:00:00Z"}],
              "tombstones": [{"conversationId":"removed-chat","ownerId":999,
                "deletedAt":"2026-10-09T12:00:00Z"}]
            }
            """;

    static final String MAPPING_JSON = """
            {"formatVersion":1,"sourceNamespace":"75b491b9-4082-48af-ad4e-50fcddbc992a",
             "entries":[{"sourceUserId":7,"sourceUsername":"source-user",
               "targetUserId":300,"targetUsername":"target-user"}]}
            """;

    private TransferFixtures() {}

    static TransferSnapshot snapshot(List<TransferSnapshot.Message> messages, TransferSnapshot.Memory memory) {
        return new TransferSnapshot(1, NAMESPACE, "psych-business-v1",
                List.of(new TransferSnapshot.User(7, "source-user")),
                List.of(new TransferSnapshot.Conversation("chat-a", "聊天", 7, TIME, TIME)),
                messages, memory == null ? List.of() : List.of(memory), List.of(), List.of());
    }

    static TransferSnapshot.Message message(long id) {
        return new TransferSnapshot.Message(id, "chat-a", "user", "消息 " + id, null, TIME);
    }

    static TransferSnapshot.Memory memory(long cursor) {
        return new TransferSnapshot.Memory("chat-a", "摘要", cursor, 3, 2, TIME);
    }
}
