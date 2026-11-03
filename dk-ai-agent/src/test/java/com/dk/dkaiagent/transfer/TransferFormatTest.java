package com.dk.dkaiagent.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static com.dk.dkaiagent.transfer.TransferException.Code.*;
import static com.dk.dkaiagent.transfer.TransferFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferFormatTest {
    @TempDir
    Path temporary;

    @Test
    void readsIndependentFixtureAndPreservesEveryContentWhitespace() throws Exception {
        byte[] exact = JSON.getBytes(StandardCharsets.UTF_8);
        var read = TransferFormat.readSnapshot(exact);
        assertEquals("  谢谢\n继续\t  ", read.value().messages().getFirst().content());
        assertEquals("  部分回答\n ", read.value().turns().getFirst().answer());
        assertEquals("legacy-chat-一", read.value().conversations().getFirst().id());
        assertEquals(999, read.value().tombstones().getFirst().ownerId());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exact)), read.sha256());
        assertEquals(read.value(), TransferFormat.readSnapshot(TransferFormat.writeSnapshot(read.value())).value());
        var changedWhitespace = TransferFormat.readSnapshot((JSON + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(read.value(), changedWhitespace.value());
        assertNotEquals(read.sha256(), changedWhitespace.sha256());

        ObjectNode encoded = (ObjectNode) new ObjectMapper().readTree(TransferFormat.writeSnapshot(read.value()));
        List<String> userFields = new ArrayList<>();
        encoded.get("users").get(0).fieldNames().forEachRemaining(userFields::add);
        assertEquals(List.of("id", "username"), userFields);
    }

    @Test
    void streamAndPathApisHaveTheSameByteBindingAndLeaveCallerStreamOpen() throws Exception {
        byte[] raw = JSON.getBytes(StandardCharsets.UTF_8);
        class ObservedInput extends ByteArrayInputStream {
            boolean closed;
            ObservedInput() { super(raw); }
            @Override public void close() { closed = true; }
        }
        ObservedInput input = new ObservedInput();
        var fromStream = TransferFormat.readSnapshot(input);
        assertFalse(input.closed);
        Path original = temporary.resolve("source.json");
        Files.write(original, raw);
        assertEquals(fromStream, TransferFormat.readSnapshot(original));
        Path written = temporary.resolve("canonical.json");
        TransferFormat.writeSnapshot(written, fromStream.value());
        assertArrayEquals(TransferFormat.writeSnapshot(fromStream.value()), Files.readAllBytes(written));
    }

    @Test
    void canonicalSerializationOrdersRowsAndDoesNotMutateCallerLists() {
        var sourceMessages = new ArrayList<>(List.of(message(31), message(2), message(15)));
        var unsorted = snapshot(sourceMessages, memory(15));
        var sorted = snapshot(List.of(message(2), message(15), message(31)), memory(15));
        assertArrayEquals(TransferFormat.writeSnapshot(sorted), TransferFormat.writeSnapshot(unsorted));
        assertEquals(List.of(31L, 2L, 15L), unsorted.messages().stream().map(TransferSnapshot.Message::id).toList());
        sourceMessages.clear();
        assertEquals(3, unsorted.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> unsorted.messages().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"passwordHash", "role", "status", "sessionToken"})
    void rejectsUnexpectedAccountFields(String field) throws Exception {
        assertRejected(INVALID_JSON, mutate(root -> object(root, "users").put(field, "confidential-value")));
    }

    @Test
    void rejectsDuplicateJsonKeysAndTrailingDocuments() {
        assertRejected(INVALID_JSON, JSON.replace("\"formatVersion\": 1,", "\"formatVersion\": 1,\"formatVersion\": 1,"));
        assertRejected(INVALID_JSON, JSON.replace("\"id\":7,", "\"id\":7,\"id\":8,"));
        assertRejected(INVALID_JSON, JSON + " {}");
        assertRejected(INVALID_JSON, JSON + " null");
        assertRejected(INVALID_JSON, JSON.substring(0, JSON.lastIndexOf('}')));
    }

    @Test
    void rejectsMissingFieldsNullPrimitivesAndScalarCoercion() throws Exception {
        assertRejected(INVALID_JSON, mutate(root -> root.remove("formatVersion")));
        assertRejected(INVALID_JSON, mutate(root -> root.remove("users")));
        assertRejected(INVALID_JSON, mutate(root -> object(root, "messages").remove("clientMsgId")));
        assertRejected(INVALID_JSON, mutate(root -> root.putNull("formatVersion")));
        assertRejected(INVALID_JSON, mutate(root -> object(root, "users").putNull("id")));
        assertRejected(INVALID_JSON, mutate(root -> root.put("formatVersion", "1")));
        assertRejected(INVALID_JSON, mutate(root -> object(root, "users").put("id", 7.5)));
        assertRejected(INVALID_JSON, mutate(root -> object(root, "users").put("username", 123)));
        assertRejected(INVALID_JSON, mutate(root -> object(root, "messages").put("clientMsgId", false)));
        assertRejected(INVALID_JSON, mutate(root -> object(root, "turns").put("fallback", "false")));
    }

    @Test
    void rejectsUnsupportedVersionsAndMalformedNamespaceOrTimestamp() throws Exception {
        assertRejected(UNSUPPORTED_VERSION, mutate(root -> root.put("formatVersion", 2)));
        assertRejected(UNSUPPORTED_VERSION, mutate(root -> root.put("schemaVersion", "psych-business-v2")));
        assertRejected(INVALID_VALUE, mutate(root -> root.put("sourceNamespace", "1-1-1-1-1")));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "messages").put("createdAt", "2026-99-09T12:00:00Z")));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "conversations").put("updatedAt", "2026-10-09T13:00:00+01:00")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "conversations", "messages", "memories", "turns", "tombstones"})
    void rejectsDuplicateBusinessPrimaryKeys(String field) throws Exception {
        assertRejected(DUPLICATE_RECORD, mutate(root -> {
            ArrayNode rows = (ArrayNode) root.get(field);
            rows.add(rows.get(0).deepCopy());
        }));
    }

    @Test
    void enforcesConversationScopedMessageAndTurnKeysWithoutConflatingNulls() throws Exception {
        String duplicates = mutate(root -> {
            ObjectNode first = object(root, "messages");
            first.put("clientMsgId", "same-key");
            ObjectNode second = first.deepCopy();
            second.put("id", 12);
            ((ArrayNode) root.get("messages")).add(second);
        });
        assertRejected(DUPLICATE_RECORD, duplicates);

        String nullable = mutate(root -> {
            ObjectNode second = object(root, "messages").deepCopy();
            second.put("id", 12);
            ((ArrayNode) root.get("messages")).add(second);
        });
        assertEquals(2, read(nullable).messages().size());

        assertRejected(DUPLICATE_RECORD, mutate(root -> {
            ObjectNode first = object(root, "turns");
            first.put("status", "RUNNING");
            ObjectNode second = first.deepCopy();
            second.put("clientMsgId", "different-key");
            ((ArrayNode) root.get("turns")).add(second);
        }));
    }

    @Test
    void rejectsLiveOrphansAndLiveTombstoneCollisionButPermitsDeletedOwner() throws Exception {
        assertRejected(INVALID_REFERENCE, mutate(root -> object(root, "conversations").put("ownerId", 404)));
        for (String field : List.of("messages", "memories", "turns")) {
            assertRejected(INVALID_REFERENCE, mutate(root -> object(root, field).put("conversationId", "unknown")));
        }
        assertRejected(INVALID_REFERENCE, mutate(root -> object(root, "tombstones").put("conversationId", "legacy-chat-一")));
        assertEquals(999, read(JSON).tombstones().getFirst().ownerId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "COMPLETED", "FAILED", "INTERRUPTED"})
    void acceptsActualPersistedTurnStatuses(String status) throws Exception {
        assertEquals(status, read(mutate(root -> object(root, "turns").put("status", status))).turns().getFirst().status());
    }

    @Test
    void rejectsInvalidTurnEnumsHashAndDigestMetadata() throws Exception {
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "turns").put("status", "DONE")));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "turns").put("effectiveMode", "auto")));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "turns").put("requestHash", "f".repeat(63))));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "turns").put("requestHash", "x".repeat(64))));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "memories").put("coveredUntilMessageId", -1)));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "memories").put("coveredMessageCount", -1)));
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "memories").put("digestChars", 1)));
        String supplementary = mutate(root -> {
            object(root, "memories").put("digest", "🙂");
            object(root, "memories").put("digestChars", 2);
        });
        assertEquals(2, read(supplementary).memories().getFirst().digestChars());
    }

    @Test
    void rejectsMalformedUtf8UnpairedSurrogatesAndPostgresqlNul() throws Exception {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xc3, 0x28},
                new byte[]{(byte) 0xc0, (byte) 0xaf}, new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
            assertEquals(INVALID_UTF8, assertThrows(TransferException.class, () -> TransferFormat.readSnapshot(bytes)).code());
        }
        assertRejected(INVALID_VALUE, mutate(root -> object(root, "messages").put("content", "before\u0000after")));
        String escapedSurrogate = JSON.replace("  谢谢\\n继续\\t  ", "\\ud800");
        assertRejected(INVALID_VALUE, escapedSurrogate);
    }

    @Test
    void enforcesDecodedUtf8FieldLimitIncludingMultibyteCharacters() {
        String exactlyOneMiB = "汉".repeat(TransferFormat.MAX_TEXT_BYTES / 3) + "x";
        assertEquals(TransferFormat.MAX_TEXT_BYTES, exactlyOneMiB.getBytes(StandardCharsets.UTF_8).length);
        var accepted = snapshot(List.of(new TransferSnapshot.Message(1, "chat-a", "user", exactlyOneMiB, null, TIME)), null);
        assertEquals(exactlyOneMiB, TransferFormat.readSnapshot(TransferFormat.writeSnapshot(accepted))
                .value().messages().getFirst().content());
        var rejected = snapshot(List.of(new TransferSnapshot.Message(1, "chat-a", "user", exactlyOneMiB + "x", null, TIME)), null);
        assertEquals(LIMIT_EXCEEDED, assertThrows(TransferException.class, () -> TransferFormat.writeSnapshot(rejected)).code());
    }

    @Test
    void enforcesAggregateRecordLimitBeforeDatabindingAndAcrossTables() {
        String excessiveJson = "{\"users\":[" + "{},".repeat(TransferFormat.MAX_RECORDS) + "{}]}";
        assertRejected(LIMIT_EXCEEDED, excessiveJson);
        var excessiveSnapshot = new TransferSnapshot(1, NAMESPACE, "psych-business-v1",
                Collections.nCopies(TransferFormat.MAX_RECORDS, new TransferSnapshot.User(7, "source-user")),
                List.of(new TransferSnapshot.Conversation("chat-a", "聊天", 7, TIME, TIME)),
                List.of(), List.of(), List.of(), List.of());
        assertEquals(LIMIT_EXCEEDED, assertThrows(TransferException.class,
                () -> TransferFormat.validateSnapshot(excessiveSnapshot)).code());
    }

    @Test
    void capsStreamConsumptionAt64MiBPlusOneByte() {
        class ExcessiveInput extends InputStream {
            long consumed;
            @Override public int read() { consumed++; return ' '; }
            @Override public int read(byte[] buffer, int offset, int length) {
                Arrays.fill(buffer, offset, offset + length, (byte) ' ');
                consumed += length;
                return length;
            }
        }
        ExcessiveInput input = new ExcessiveInput();
        assertEquals(LIMIT_EXCEEDED, assertThrows(TransferException.class, () -> TransferFormat.readSnapshot(input)).code());
        assertEquals((long) TransferFormat.MAX_INPUT_BYTES + 1, input.consumed);
    }

    @Test
    void errorsNeverCarrySourceContentOrParserCauses() {
        String secret = "private-counseling-answer-secret";
        TransferException error = assertThrows(TransferException.class, () ->
                TransferFormat.readSnapshot(("{\"unexpected\":\"" + secret + "\"}").getBytes(StandardCharsets.UTF_8)));
        assertEquals("Invalid transfer JSON", error.getMessage());
        assertFalse(error.toString().contains(secret));
        assertNull(error.getCause());
        assertEquals("Transfer I/O failed", assertThrows(TransferException.class,
                () -> TransferFormat.readSnapshot(temporary.resolve(secret))).getMessage());
    }

    private static TransferSnapshot read(String json) {
        return TransferFormat.readSnapshot(json.getBytes(StandardCharsets.UTF_8)).value();
    }

    private static void assertRejected(TransferException.Code code, String json) {
        assertEquals(code, assertThrows(TransferException.class, () -> read(json)).code());
    }

    private static ObjectNode object(ObjectNode root, String table) {
        return (ObjectNode) root.get(table).get(0);
    }

    private static String mutate(Consumer<ObjectNode> edit) throws Exception {
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(JSON);
        edit.accept(root);
        return root.toString();
    }
}
