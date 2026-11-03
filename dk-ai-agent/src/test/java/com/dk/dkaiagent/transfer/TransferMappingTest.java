package com.dk.dkaiagent.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.dk.dkaiagent.transfer.TransferException.Code.*;
import static com.dk.dkaiagent.transfer.TransferFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferMappingTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesBothIdsAndBothNamesWithoutRequiringNamesToBeEqual() {
        var snapshot = source();
        var mapping = read(MAPPING_JSON);
        assertEquals(Map.of(7L, 300L), mapping.resolve(snapshot, Map.of(300L, "target-user")));
        assertThrows(UnsupportedOperationException.class, () ->
                mapping.resolve(snapshot, Map.of(300L, "target-user")).put(8L, 301L));
    }

    @Test
    void sameNameNeverSubstitutesForMissingExplicitIdentityEvidence() {
        var mapping = read(MAPPING_JSON);
        // A target account with the reviewed name but a different ID cannot satisfy the mapping.
        assertMappingFailure(mapping, source(), Map.of(301L, "target-user"));
        assertMappingFailure(mapping, source(), Map.of(300L, "source-user"));
        assertMappingFailure(read(MAPPING_JSON.replace("\"sourceUserId\":7", "\"sourceUserId\":8")),
                source(), Map.of(300L, "target-user"));
        assertMappingFailure(read(MAPPING_JSON.replace("source-user", "SOURCE-USER")),
                source(), Map.of(300L, "target-user"));
        assertMappingFailure(new TransferMapping(1, NAMESPACE, List.of()), source(), Map.of(7L, "source-user"));
    }

    @Test
    void namespaceMismatchAndRenamedAccountsInvalidatePreviouslyReviewedEvidence() {
        var mapping = read(MAPPING_JSON);
        assertMappingFailure(new TransferMapping(1, "256dbb62-40d2-4465-873f-65127a24df97", mapping.entries()),
                source(), Map.of(300L, "target-user"));
        assertMappingFailure(mapping, source(), Map.of(300L, "renamed-target"));
        var renamedSource = new TransferSnapshot(1, NAMESPACE, "psych-business-v1",
                List.of(new TransferSnapshot.User(7, "renamed-source")), source().conversations(),
                List.of(), List.of(), List.of(), List.of());
        assertMappingFailure(mapping, renamedSource, Map.of(300L, "target-user"));
    }

    @Test
    void everySourceAccountNeedsExactlyOneEntryAndExtraMappingsAreRejected() {
        var twoUsers = new TransferSnapshot(1, NAMESPACE, "psych-business-v1",
                List.of(new TransferSnapshot.User(7, "source-user"), new TransferSnapshot.User(8, "other-source")),
                source().conversations(), List.of(), List.of(), List.of(), List.of());
        var first = new TransferMapping.Entry(7, "source-user", 300, "target-user");
        var second = new TransferMapping.Entry(8, "other-source", 301, "other-target");
        assertMappingFailure(new TransferMapping(1, NAMESPACE, List.of(first)), twoUsers,
                Map.of(300L, "target-user", 301L, "other-target"));
        assertMappingFailure(new TransferMapping(1, NAMESPACE, List.of(first, second)), source(),
                Map.of(300L, "target-user", 301L, "other-target"));
        assertEquals(Map.of(7L, 300L, 8L, 301L), new TransferMapping(1, NAMESPACE, List.of(second, first))
                .resolve(twoUsers, Map.of(300L, "target-user", 301L, "other-target")));
    }

    @Test
    void rejectsDuplicateSourceIdsAndManyToOneTargetIdsBeforeMerging() {
        var first = new TransferMapping.Entry(7, "source-user", 300, "target-user");
        for (var second : List.of(
                new TransferMapping.Entry(7, "another-source", 301, "another-target"),
                new TransferMapping.Entry(8, "another-source", 300, "target-user"))) {
            var mapping = new TransferMapping(1, NAMESPACE, List.of(first, second));
            assertEquals(DUPLICATE_RECORD, assertThrows(TransferException.class,
                    () -> TransferFormat.validateMapping(mapping)).code());
        }
    }

    @Test
    void mappingJsonIsStrictAndHashBindsItsExactBytes() throws Exception {
        byte[] exact = MAPPING_JSON.getBytes(StandardCharsets.UTF_8);
        var loaded = TransferFormat.readMapping(exact);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exact)), loaded.sha256());
        var whitespace = TransferFormat.readMapping(("\n" + MAPPING_JSON).getBytes(StandardCharsets.UTF_8));
        assertEquals(loaded.value(), whitespace.value());
        assertNotEquals(loaded.sha256(), whitespace.sha256());
        assertEquals(loaded.value(), TransferFormat.readMapping(TransferFormat.writeMapping(loaded.value())).value());

        assertReadFailure(INVALID_JSON, MAPPING_JSON.replace("\"targetUserId\":300", "\"targetUserId\":\"300\""));
        assertReadFailure(INVALID_JSON, MAPPING_JSON.replace("\"targetUserId\":300", "\"targetUserId\":null"));
        assertReadFailure(INVALID_JSON, MAPPING_JSON.replace("\"targetUserId\":300", "\"targetUserId\":300,\"extra\":true"));
        assertReadFailure(INVALID_JSON, MAPPING_JSON.replace("\"targetUserId\":300", "\"targetUserId\":300,\"targetUserId\":301"));
        assertReadFailure(INVALID_JSON, MAPPING_JSON + "{}");
        assertReadFailure(UNSUPPORTED_VERSION, MAPPING_JSON.replace("\"formatVersion\":1", "\"formatVersion\":0"));
        assertReadFailure(INVALID_VALUE, MAPPING_JSON.replace("\"targetUserId\":300", "\"targetUserId\":0"));
    }

    @Test
    void canonicalMappingSortsEntriesAndSupportsPathIo() throws Exception {
        var first = new TransferMapping.Entry(7, "source-user", 300, "target-user");
        var second = new TransferMapping.Entry(8, "other-source", 301, "other-target");
        var forward = new TransferMapping(1, NAMESPACE, List.of(first, second));
        var reverse = new TransferMapping(1, NAMESPACE, List.of(second, first));
        assertArrayEquals(TransferFormat.writeMapping(forward), TransferFormat.writeMapping(reverse));
        Path path = temporary.resolve("mapping.json");
        TransferFormat.writeMapping(path, reverse);
        assertArrayEquals(TransferFormat.writeMapping(forward), Files.readAllBytes(path));
        assertEquals(forward, TransferFormat.readMapping(path).value());
    }

    private static TransferSnapshot source() {
        return snapshot(List.of(), null);
    }

    private static TransferMapping read(String json) {
        return TransferFormat.readMapping(json.getBytes(StandardCharsets.UTF_8)).value();
    }

    private static void assertMappingFailure(TransferMapping mapping, TransferSnapshot snapshot,
                                             Map<Long, String> targets) {
        assertEquals(INVALID_MAPPING, assertThrows(TransferException.class, () -> mapping.resolve(snapshot, targets)).code());
    }

    private static void assertReadFailure(TransferException.Code code, String json) {
        assertEquals(code, assertThrows(TransferException.class, () -> read(json)).code());
    }
}
