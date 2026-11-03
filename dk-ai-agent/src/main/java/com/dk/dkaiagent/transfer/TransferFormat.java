package com.dk.dkaiagent.transfer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.function.Consumer;

import static com.dk.dkaiagent.transfer.TransferException.Code.*;

/** Strict, bounded, deterministic UTF-8 wire format. No application ObjectMapper is consulted. */
public final class TransferFormat {
    public static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_RECORDS = 100_000;
    public static final int MAX_TEXT_BYTES = 1024 * 1024;

    private static final ObjectMapper MAPPER = createMapper();

    private TransferFormat() {}

    /** sha256 binds the exact bytes read, including otherwise insignificant JSON whitespace. */
    public record ReadResult<T>(T value, String sha256) {}

    public static ReadResult<TransferSnapshot> readSnapshot(Path path) {
        return readChecked(readPath(path), TransferSnapshot.class, TransferValidation::snapshot);
    }

    public static ReadResult<TransferSnapshot> readSnapshot(InputStream input) {
        return readChecked(readBounded(input), TransferSnapshot.class, TransferValidation::snapshot);
    }

    public static ReadResult<TransferSnapshot> readSnapshot(byte[] bytes) {
        return readChecked(copyBytes(bytes), TransferSnapshot.class, TransferValidation::snapshot);
    }

    public static ReadResult<TransferMapping> readMapping(Path path) {
        return readChecked(readPath(path), TransferMapping.class, TransferValidation::mapping);
    }

    public static ReadResult<TransferMapping> readMapping(InputStream input) {
        return readChecked(readBounded(input), TransferMapping.class, TransferValidation::mapping);
    }

    public static ReadResult<TransferMapping> readMapping(byte[] bytes) {
        return readChecked(copyBytes(bytes), TransferMapping.class, TransferValidation::mapping);
    }

    public static void validateSnapshot(TransferSnapshot snapshot) {
        TransferValidation.snapshot(snapshot);
    }

    public static void validateMapping(TransferMapping mapping) {
        TransferValidation.mapping(mapping);
    }

    public static byte[] writeSnapshot(TransferSnapshot snapshot) {
        validateSnapshot(snapshot);
        // Arrays have canonical key order as well as properties, independent of JDBC row order.
        TransferSnapshot ordered = new TransferSnapshot(snapshot.formatVersion(), snapshot.sourceNamespace(),
                snapshot.schemaVersion(),
                snapshot.users().stream().sorted(Comparator.comparingLong(TransferSnapshot.User::id)).toList(),
                snapshot.conversations().stream().sorted(Comparator.comparing(TransferSnapshot.Conversation::id)).toList(),
                snapshot.messages().stream().sorted(Comparator.comparingLong(TransferSnapshot.Message::id)).toList(),
                snapshot.memories().stream().sorted(Comparator.comparing(TransferSnapshot.Memory::conversationId)).toList(),
                snapshot.turns().stream().sorted(Comparator.comparing(TransferSnapshot.Turn::conversationId)
                        .thenComparing(TransferSnapshot.Turn::clientMsgId)).toList(),
                snapshot.tombstones().stream().sorted(Comparator.comparing(TransferSnapshot.Tombstone::conversationId)).toList());
        return writeChecked(ordered);
    }

    public static byte[] writeMapping(TransferMapping mapping) {
        validateMapping(mapping);
        return writeChecked(new TransferMapping(mapping.formatVersion(), mapping.sourceNamespace(),
                mapping.entries().stream().sorted(Comparator.comparingLong(TransferMapping.Entry::sourceUserId)).toList()));
    }

    public static void writeSnapshot(Path path, TransferSnapshot snapshot) {
        writePath(path, writeSnapshot(snapshot));
    }

    public static void writeMapping(Path path, TransferMapping mapping) {
        writePath(path, writeMapping(mapping));
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(12)
                        .maxStringLength(MAX_TEXT_BYTES)
                        .maxNameLength(128)
                        .maxNumberLength(20)
                        .build())
                .build();
        ObjectMapper mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        // Jackson's textual coercion is separate from numeric/boolean scalar coercion.
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float,
                CoercionInputShape.Boolean}) {
            mapper.coercionConfigFor(String.class).setCoercion(shape, CoercionAction.Fail);
        }
        return mapper;
    }

    private static byte[] copyBytes(byte[] bytes) {
        if (bytes == null) throw new TransferException(INVALID_STRUCTURE);
        if (bytes.length > MAX_INPUT_BYTES) throw new TransferException(LIMIT_EXCEEDED);
        return bytes.clone();
    }

    private static byte[] readPath(Path path) {
        if (path == null) throw new TransferException(INVALID_STRUCTURE);
        try (InputStream input = Files.newInputStream(path)) {
            return readBounded(input);
        } catch (IOException error) {
            throw new TransferException(IO_ERROR);
        }
    }

    /** Caller-owned streams remain open, and no more than the limit plus one byte is consumed. */
    private static byte[] readBounded(InputStream input) {
        if (input == null) throw new TransferException(INVALID_STRUCTURE);
        try {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) throw new TransferException(LIMIT_EXCEEDED);
            return bytes;
        } catch (IOException error) {
            throw new TransferException(IO_ERROR);
        }
    }

    private static <T> ReadResult<T> readChecked(byte[] bytes, Class<T> type, Consumer<T> validate) {
        requireUtf8(bytes);
        try {
            preflight(bytes);
            T value = MAPPER.readValue(bytes, type);
            validate.accept(value);
            return new ReadResult<>(value, sha256(bytes));
        } catch (TransferException error) {
            throw error;
        } catch (StreamConstraintsException error) {
            throw new TransferException(LIMIT_EXCEEDED);
        } catch (IOException | RuntimeException error) {
            // Neither Jackson's exception text nor its cause may disclose a confidential field.
            throw new TransferException(INVALID_JSON);
        }
    }

    private static void requireUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer scratch = CharBuffer.allocate(8192);
        while (true) {
            var result = decoder.decode(input, scratch, true);
            if (result.isError()) throw new TransferException(INVALID_UTF8);
            if (result.isUnderflow()) break;
            scratch.clear();
        }
    }

    /** Stop excessive root-array records before databinding can allocate millions of objects. */
    private static void preflight(byte[] bytes) throws IOException {
        int depth = 0;
        int records = 0;
        try (JsonParser parser = MAPPER.createParser(bytes)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (token == JsonToken.START_OBJECT && depth == 2 && ++records > MAX_RECORDS) {
                        throw new TransferException(LIMIT_EXCEEDED);
                    }
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
            }
        }
    }

    private static byte[] writeChecked(Object value) {
        BoundedOutput output = new BoundedOutput();
        try {
            MAPPER.writeValue(output, value);
            return output.bytes.toByteArray();
        } catch (IOException | RuntimeException error) {
            throw new TransferException(output.exceeded ? LIMIT_EXCEEDED : INVALID_JSON);
        }
    }

    private static void writePath(Path path, byte[] bytes) {
        if (path == null) throw new TransferException(INVALID_STRUCTURE);
        try {
            Files.write(path, bytes);
        } catch (IOException error) {
            throw new TransferException(IO_ERROR);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new TransferException(INVALID_STRUCTURE);
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        private boolean exceeded;

        @Override
        public void write(int value) {
            requireCapacity(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] source, int offset, int count) {
            requireCapacity(count);
            bytes.write(source, offset, count);
        }

        private void requireCapacity(int count) {
            if ((long) bytes.size() + count > MAX_INPUT_BYTES) {
                exceeded = true;
                throw new TransferException(LIMIT_EXCEEDED);
            }
        }
    }
}
