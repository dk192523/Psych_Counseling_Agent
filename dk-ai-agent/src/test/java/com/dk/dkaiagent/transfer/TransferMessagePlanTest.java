package com.dk.dkaiagent.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.dk.dkaiagent.transfer.TransferException.Code.INVALID_REFERENCE;
import static com.dk.dkaiagent.transfer.TransferFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferMessagePlanTest {
    @Test
    void existingWatermarkUsesTheRealMessageAllocationWithoutMarker() {
        var snapshot = snapshot(List.of(message(31), message(2), message(15)), memory(15));
        var plan = TransferMessagePlan.forConversation(snapshot, "chat-a");
        assertEquals("chat-a", plan.conversationId());
        assertEquals(15, plan.sourceCursor());
        assertEquals(List.of(2L, 15L, 31L), ids(plan));
        assertTrue(plan.steps().stream().noneMatch(TransferMessagePlan.Step::virtualMarker));
        Map<Long, Long> allocated = Map.of(2L, 600L, 15L, 601L, 31L, 602L);
        assertEquals(601, plan.remapCursor(allocated));
        assertDigestBoundaryPreserved(snapshot, plan, allocated);
    }

    @Test
    void prunedWatermarkConsumesOneSequenceSlotBetweenExistingMessages() {
        var snapshot = snapshot(List.of(message(20), message(3)), memory(10));
        var plan = TransferMessagePlan.forConversation(snapshot, "chat-a");
        assertEquals(List.of(3L, 10L, 20L), ids(plan));
        assertFalse(plan.steps().getFirst().virtualMarker());
        assertTrue(plan.steps().get(1).virtualMarker());
        assertNull(plan.steps().get(1).message());
        assertFalse(plan.steps().getLast().virtualMarker());
        assertEquals(2, plan.steps().stream().filter(step -> !step.virtualMarker()).count());
        Map<Long, Long> allocated = Map.of(3L, 1000L, 10L, 1001L, 20L, 1002L);
        assertEquals(1001, plan.remapCursor(allocated));
        assertDigestBoundaryPreserved(snapshot, plan, allocated);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 15, 99})
    void prunedWatermarkCanFallBeforeBetweenOrAfterRetainedRows(long cursor) {
        var snapshot = snapshot(List.of(message(20), message(10)), memory(cursor));
        var plan = TransferMessagePlan.forConversation(snapshot, "chat-a");
        assertEquals(1, plan.steps().stream().filter(TransferMessagePlan.Step::virtualMarker).count());
        Map<Long, Long> allocations = switch ((int) cursor) {
            case 1 -> Map.of(1L, 500L, 10L, 501L, 20L, 502L);
            case 15 -> Map.of(10L, 500L, 15L, 501L, 20L, 502L);
            default -> Map.of(10L, 500L, 20L, 501L, 99L, 502L);
        };
        assertDigestBoundaryPreserved(snapshot, plan, allocations);
    }

    @Test
    void allPrunedMessagesStillProduceANonzeroTargetWatermarkWithoutInsertingContent() {
        var plan = TransferMessagePlan.forConversation(snapshot(List.of(), memory(23)), "chat-a");
        assertEquals(1, plan.steps().size());
        assertEquals(23, plan.steps().getFirst().sourceMessageId());
        assertTrue(plan.steps().getFirst().virtualMarker());
        assertEquals(900, plan.remapCursor(Map.of(23L, 900L)));
        assertTrue(plan.steps().stream().allMatch(step -> step.message() == null));
    }

    @Test
    void zeroWatermarkAndAbsentMemoryNeverConsumeAVirtualSlot() {
        for (var memory : new TransferSnapshot.Memory[]{memory(0), null}) {
            var plan = TransferMessagePlan.forConversation(snapshot(List.of(message(11), message(22)), memory), "chat-a");
            assertEquals(0, plan.sourceCursor());
            assertEquals(List.of(11L, 22L), ids(plan));
            assertTrue(plan.steps().stream().noneMatch(TransferMessagePlan.Step::virtualMarker));
            assertEquals(0, plan.remapCursor(Map.of()));
        }
        var empty = TransferMessagePlan.forConversation(snapshot(List.of(), memory(0)), "chat-a");
        assertTrue(empty.steps().isEmpty());
        assertEquals(0, empty.remapCursor(Map.of()));
    }

    @Test
    void extantWatermarkInAnotherConversationIsCorruptionRatherThanPruning() {
        var source = new TransferSnapshot(1, NAMESPACE, "psych-business-v1",
                List.of(new TransferSnapshot.User(7, "source-user")),
                List.of(conversation("chat-a"), conversation("chat-b")),
                List.of(message(1), new TransferSnapshot.Message(42, "chat-b", "assistant", "foreign", null, TIME)),
                List.of(memory(42)), List.of(), List.of());
        assertEquals(INVALID_REFERENCE, assertThrows(TransferException.class,
                () -> TransferFormat.validateSnapshot(source)).code());
        assertEquals(INVALID_REFERENCE, assertThrows(TransferException.class,
                () -> TransferMessagePlan.forConversation(source, "chat-a")).code());
    }

    @Test
    void missingNumericCursorsAreScopedToTheirOwnConversationPlans() {
        var source = new TransferSnapshot(1, NAMESPACE, "psych-business-v1",
                List.of(new TransferSnapshot.User(7, "source-user")),
                List.of(conversation("chat-b"), conversation("chat-a")),
                List.of(message(10), new TransferSnapshot.Message(20, "chat-b", "user", "second", null, TIME)),
                List.of(memory(5), new TransferSnapshot.Memory("chat-b", "摘要", 5, 3, 2, TIME)), List.of(), List.of());
        var plans = TransferMessagePlan.allConversations(source);
        assertEquals(List.of("chat-a", "chat-b"), plans.keySet().stream().toList());
        assertEquals(100, plans.get("chat-a").remapCursor(Map.of(5L, 100L, 10L, 101L)));
        assertEquals(200, plans.get("chat-b").remapCursor(Map.of(5L, 200L, 20L, 201L)));
        assertThrows(UnsupportedOperationException.class, plans::clear);
    }

    @Test
    void remappingRejectsIncompleteOrNonmonotoneAllocations() {
        var plan = TransferMessagePlan.forConversation(snapshot(List.of(message(2), message(20)), memory(10)), "chat-a");
        for (var allocations : List.of(Map.of(2L, 100L, 20L, 102L),
                Map.of(2L, 100L, 10L, 99L, 20L, 102L),
                Map.of(2L, 100L, 10L, 100L, 20L, 102L),
                Map.of(2L, -1L, 10L, 101L, 20L, 102L))) {
            assertEquals(INVALID_REFERENCE, assertThrows(TransferException.class, () -> plan.remapCursor(allocations)).code());
        }
        assertEquals(INVALID_REFERENCE, assertThrows(TransferException.class, () -> plan.remapCursor(null)).code());
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
    }

    @Test
    void refusesAPlanForAnUnknownConversation() {
        assertEquals(INVALID_REFERENCE, assertThrows(TransferException.class,
                () -> TransferMessagePlan.forConversation(snapshot(List.of(), null), "unknown")).code());
    }

    private static List<Long> ids(TransferMessagePlan.Plan plan) {
        return plan.steps().stream().map(TransferMessagePlan.Step::sourceMessageId).toList();
    }

    /** Verify the business invariant: exactly the same retained messages remain covered by memory. */
    private static void assertDigestBoundaryPreserved(TransferSnapshot snapshot, TransferMessagePlan.Plan plan,
                                                     Map<Long, Long> allocations) {
        long targetCursor = plan.remapCursor(allocations);
        for (var message : snapshot.messages()) {
            assertEquals(message.id() <= plan.sourceCursor(), allocations.get(message.id()) <= targetCursor);
        }
    }

    private static TransferSnapshot.Conversation conversation(String id) {
        return new TransferSnapshot.Conversation(id, "聊天", 7, TIME, TIME);
    }
}
