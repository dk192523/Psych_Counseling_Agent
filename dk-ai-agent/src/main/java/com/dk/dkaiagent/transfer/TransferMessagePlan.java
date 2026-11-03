package com.dk.dkaiagent.transfer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.dk.dkaiagent.transfer.TransferException.Code.INVALID_REFERENCE;

/**
 * A pure sequence-allocation plan. A marker consumes a target message sequence value but must not
 * create a message row. This preserves the digest boundary even when all covered rows were pruned.
 */
public final class TransferMessagePlan {
    private TransferMessagePlan() {}

    public record Step(long sourceMessageId, TransferSnapshot.Message message) {
        public boolean virtualMarker() {
            return message == null;
        }
    }

    public record Plan(String conversationId, long sourceCursor, List<Step> steps) {
        public Plan {
            steps = List.copyOf(steps);
        }

        /** The supplied allocations must follow the plan's strictly increasing target sequence. */
        public long remapCursor(Map<Long, Long> allocatedIds) {
            if (sourceCursor == 0) return 0;
            if (allocatedIds == null) throw new TransferException(INVALID_REFERENCE);
            long previous = 0;
            for (Step step : steps) {
                Long target = allocatedIds.get(step.sourceMessageId());
                if (target == null || target <= previous) throw new TransferException(INVALID_REFERENCE);
                previous = target;
            }
            Long mapped = allocatedIds.get(sourceCursor);
            if (mapped == null || mapped <= 0) throw new TransferException(INVALID_REFERENCE);
            return mapped;
        }
    }

    public static Plan forConversation(TransferSnapshot snapshot, String conversationId) {
        TransferFormat.validateSnapshot(snapshot);
        if (conversationId == null || snapshot.conversations().stream()
                .noneMatch(conversation -> conversationId.equals(conversation.id()))) {
            throw new TransferException(INVALID_REFERENCE);
        }
        List<TransferSnapshot.Message> messages = snapshot.messages().stream()
                .filter(message -> conversationId.equals(message.conversationId())).toList();
        long cursor = snapshot.memories().stream().filter(memory -> conversationId.equals(memory.conversationId()))
                .mapToLong(TransferSnapshot.Memory::coveredUntilMessageId).findFirst().orElse(0);
        return build(conversationId, cursor, messages);
    }

    /** Group once for a full import: O(records + messages log messages), rather than C*M scans. */
    public static Map<String, Plan> allConversations(TransferSnapshot snapshot) {
        TransferFormat.validateSnapshot(snapshot);
        Map<String, List<TransferSnapshot.Message>> grouped = new TreeMap<>();
        snapshot.conversations().forEach(conversation -> grouped.put(conversation.id(), new ArrayList<>()));
        snapshot.messages().forEach(message -> grouped.get(message.conversationId()).add(message));
        Map<String, Long> cursors = new LinkedHashMap<>();
        snapshot.memories().forEach(memory -> cursors.put(memory.conversationId(), memory.coveredUntilMessageId()));
        Map<String, Plan> plans = new LinkedHashMap<>();
        grouped.forEach((conversationId, messages) ->
                plans.put(conversationId, build(conversationId, cursors.getOrDefault(conversationId, 0L), messages)));
        return java.util.Collections.unmodifiableMap(plans);
    }

    private static Plan build(String conversationId, long cursor, List<TransferSnapshot.Message> messages) {
        List<Step> steps = new ArrayList<>();
        boolean cursorPresent = false;
        for (var message : messages) {
            steps.add(new Step(message.id(), message));
            cursorPresent |= message.id() == cursor;
        }
        if (cursor > 0 && !cursorPresent) steps.add(new Step(cursor, null));
        steps.sort(Comparator.comparingLong(Step::sourceMessageId));
        return new Plan(conversationId, cursor, steps);
    }
}
