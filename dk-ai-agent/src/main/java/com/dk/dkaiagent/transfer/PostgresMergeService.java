package com.dk.dkaiagent.transfer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One-time additive import. The connection must be dedicated and in auto-commit mode; this class
 * owns its transaction. No source account, password, permission or existing business row is written.
 * Sequence consumption is deliberately not transactional: rollback may leave harmless ID gaps.
 */
public final class PostgresMergeService {
    private static final int ADVISORY_LOCK_CLASS = 0x50535943;
    private static final int ADVISORY_LOCK_KEY = 0x54524631;
    private static final String LOCK_BUSINESS = """
            LOCK TABLE public.psych_user, public.psych_conversation, public.psych_chat_message,
                public.psych_conversation_memory, public.psych_chat_turn,
                public.psych_conversation_tombstone IN SHARE ROW EXCLUSIVE MODE
            """;

    public record Result(boolean noOp, String sourceNamespace, String planSha256,
                         TransferMergePlan.Counts counts) {}

    /** A repeatable, read-only preview; no DDL, sequence allocation, locks or business writes. */
    public TransferMergePlan plan(Connection connection, byte[] snapshotBytes, byte[] mappingBytes,
                                  String targetInstanceId, boolean allowRunningToFailed) throws SQLException {
        PreparedInput input = prepare(snapshotBytes, mappingBytes, targetInstanceId, allowRunningToFailed);
        requireDedicatedConnection(connection);
        ConnectionState original = ConnectionState.of(connection);
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            timeouts(connection);
            TransferDatabaseSchema.validatePostgres(connection);
            TransferMergePlan plan = makePlan(connection, input, targetInstanceId, allowRunningToFailed);
            Ledger ledger = inspectLedger(connection, input.snapshot().sourceNamespace());
            if (ledger != null) {
                requireMatchingLedger(ledger, plan);
                connection.commit();
                return imported(plan);
            }
            validateTarget(connection, input);
            connection.commit();
            return plan;
        } catch (SQLException failure) {
            rollback(connection);
            throw safeSqlFailure(failure);
        } catch (RuntimeException failure) {
            rollback(connection);
            throw failure;
        } finally {
            original.restore(connection);
        }
    }

    /** Applies only the exact reviewed plan, or returns a durable no-op for its successful retry. */
    public Result apply(Connection connection, byte[] snapshotBytes, byte[] mappingBytes,
                        String targetInstanceId, boolean allowRunningToFailed,
                        String expectedPlanSha256) throws SQLException {
        PreparedInput input = prepare(snapshotBytes, mappingBytes, targetInstanceId, allowRunningToFailed);
        if (expectedPlanSha256 == null || !expectedPlanSha256.matches("[a-f0-9]{64}")) {
            throw rejected("EXPECTED_PLAN_REQUIRED");
        }
        requireDedicatedConnection(connection);
        // Preflight is outside our transaction. The first snapshot inside it is acquired only after
        // all business locks: a concurrent writer that committed while we waited is then visible.
        try {
            TransferDatabaseSchema.validatePostgres(connection);
        } catch (SQLException failure) {
            throw safeSqlFailure(failure);
        }
        ConnectionState original = ConnectionState.of(connection);
        try {
            connection.setReadOnly(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            timeouts(connection);
            execute(connection, LOCK_BUSINESS);
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                statement.setInt(1, ADVISORY_LOCK_CLASS);
                statement.setInt(2, ADVISORY_LOCK_KEY);
                statement.execute();
            }
            TransferDatabaseSchema.validatePostgres(connection);
            TransferMergePlan plan = makePlan(connection, input, targetInstanceId, allowRunningToFailed);
            if (!plan.planSha256().equals(expectedPlanSha256)) throw rejected("PLAN_CHANGED");
            Ledger ledger = inspectLedger(connection, input.snapshot().sourceNamespace());
            // This precedes live UUID/account checks: deleting imported target data must never
            // turn an exact retry into a resurrection operation.
            if (ledger != null) {
                requireMatchingLedger(ledger, plan);
                connection.commit();
                return new Result(true, plan.sourceNamespace(), plan.planSha256(), plan.counts());
            }
            Map<Long, Long> owners = validateTarget(connection, input);
            ensureLedger(connection);
            insertImport(connection, plan);
            merge(connection, input, owners);
            connection.commit();
            return new Result(false, plan.sourceNamespace(), plan.planSha256(), plan.counts());
        } catch (SQLException failure) {
            rollback(connection);
            throw safeSqlFailure(failure);
        } catch (RuntimeException failure) {
            rollback(connection);
            throw failure;
        } finally {
            original.restore(connection);
        }
    }

    private static PreparedInput prepare(byte[] snapshotBytes, byte[] mappingBytes, String targetInstanceId,
                                          boolean allowRunningToFailed) throws SQLException {
        if (targetInstanceId == null || targetInstanceId.isBlank() || targetInstanceId.length() > 256
                || targetInstanceId.codePoints().anyMatch(Character::isISOControl)) {
            throw rejected("TARGET_INSTANCE_REQUIRED");
        }
        var snapshot = TransferFormat.readSnapshot(snapshotBytes);
        var mapping = TransferFormat.readMapping(mappingBytes);
        if (!snapshot.value().sourceNamespace().equals(mapping.value().sourceNamespace())) {
            throw rejected("MAPPING_NAMESPACE_MISMATCH");
        }
        long running = snapshot.value().turns().stream().filter(turn -> "RUNNING".equals(turn.status())).count();
        if (running > 0 && !allowRunningToFailed) throw rejected("RUNNING_CONVERSION_REQUIRED");
        var messagePlans = TransferMessagePlan.allConversations(snapshot.value());
        Set<Long> users = new HashSet<>();
        snapshot.value().users().forEach(user -> users.add(user.id()));
        Set<Long> deletedOwners = new TreeSet<>();
        snapshot.value().tombstones().stream().map(TransferSnapshot.Tombstone::ownerId)
                .filter(owner -> !users.contains(owner)).forEach(deletedOwners::add);
        long markers = messagePlans.values().stream().flatMap(plan -> plan.steps().stream())
                .filter(TransferMessagePlan.Step::virtualMarker).count();
        var value = snapshot.value();
        var counts = new TransferMergePlan.Counts(value.users().size(), value.conversations().size(),
                value.messages().size(), value.memories().size(), value.turns().size(), value.tombstones().size(),
                deletedOwners.size(), markers, running);
        return new PreparedInput(value, mapping.value(), snapshot.sha256(), mapping.sha256(), messagePlans,
                Set.copyOf(deletedOwners), counts);
    }

    private record PreparedInput(TransferSnapshot snapshot, TransferMapping mapping, String snapshotSha256,
                                 String mappingSha256, Map<String, TransferMessagePlan.Plan> messagePlans,
                                 Set<Long> deletedOwners, TransferMergePlan.Counts counts) {}

    private static TransferMergePlan makePlan(Connection connection, PreparedInput input, String instance,
                                               boolean allowRunning) throws SQLException {
        String identity = targetIdentity(connection, instance);
        List<String> accounts = new ArrayList<>();
        input.mapping().entries().stream().sorted(Comparator.comparingLong(TransferMapping.Entry::sourceUserId))
                .forEach(entry -> {
                    accounts.add(Long.toString(entry.sourceUserId()));
                    accounts.add(Long.toString(entry.targetUserId()));
                    accounts.add(entry.targetUsername());
                });
        String mapped = digest(accounts);
        var counts = input.counts();
        String sha = digest(List.of("psych-transfer-merge-plan-v1", input.snapshot().sourceNamespace(),
                Integer.toString(input.snapshot().formatVersion()), input.snapshot().schemaVersion(),
                input.snapshotSha256(), input.mappingSha256(), identity, mapped, Boolean.toString(allowRunning),
                Long.toString(counts.mappedUsers()), Long.toString(counts.conversations()),
                Long.toString(counts.messages()), Long.toString(counts.memories()), Long.toString(counts.turns()),
                Long.toString(counts.tombstones()), Long.toString(counts.reservedDeletedOwners()),
                Long.toString(counts.watermarkReservations()), Long.toString(counts.runningTurnsConverted())));
        return new TransferMergePlan(input.snapshot().formatVersion(), input.snapshot().sourceNamespace(),
                input.snapshotSha256(), input.mappingSha256(), identity, mapped, allowRunning, counts, sha, false);
    }

    private static TransferMergePlan imported(TransferMergePlan plan) {
        return new TransferMergePlan(plan.formatVersion(), plan.sourceNamespace(), plan.snapshotSha256(),
                plan.mappingSha256(), plan.targetIdentitySha256(), plan.mappedAccountsSha256(),
                plan.allowRunningToFailed(), plan.counts(), plan.planSha256(), true);
    }

    private static String targetIdentity(Connection connection, String instance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT current_database(), d.oid::text, COALESCE(host(inet_server_addr()), ''),
                    COALESCE(inet_server_port()::text, '')
                FROM pg_catalog.pg_database d WHERE d.datname = current_database()
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw rejected("TARGET_IDENTITY_UNAVAILABLE");
            return digest(List.of("psych-transfer-target-v1", instance,
                    digest(List.of(connection.getMetaData().getURL())), result.getString(1), result.getString(2),
                    result.getString(3), result.getString(4)));
        }
    }

    private static Map<Long, Long> validateTarget(Connection connection, PreparedInput input) throws SQLException {
        Map<Long, String> targetUsers = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, username FROM public.psych_user");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) targetUsers.put(result.getLong(1), result.getString(2));
        }
        Map<Long, Long> resolved = input.mapping().resolve(input.snapshot(), targetUsers);
        Set<String> ids = new HashSet<>();
        input.snapshot().conversations().forEach(conversation -> ids.add(conversation.id()));
        input.snapshot().tombstones().forEach(tombstone -> ids.add(tombstone.conversationId()));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM public.psych_conversation
                UNION ALL SELECT conversation_id FROM public.psych_conversation_tombstone
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) if (ids.contains(result.getString(1))) throw rejected("TARGET_UUID_CONFLICT");
        }
        // A stale sequence cannot be repaired here: setval would change target state and can race
        // with application allocations. The operator must repair it independently before planning.
        sequence(connection, "psych_user");
        sequence(connection, "psych_chat_message");
        return resolved;
    }

    private record Sequence(String qualifiedName, long maximum) {}

    private static Sequence sequence(Connection connection, String table) throws SQLException {
        String name;
        String schema;
        long maxValue;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT n.nspname, c.relname, s.seqincrement, s.seqmin, s.seqmax, s.seqcycle
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_catalog.pg_sequence s ON s.seqrelid=c.oid
                WHERE c.oid=pg_catalog.pg_get_serial_sequence(?, 'id')::regclass
                """)) {
            statement.setString(1, "public." + table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(3) != 1 || result.getLong(4) < 1 || result.getBoolean(6)) {
                    throw rejected("UNSAFE_TARGET_SEQUENCE");
                }
                schema = result.getString(1);
                name = result.getString(2);
                maxValue = result.getLong(5);
            }
        }
        String qualified = quote(schema) + "." + quote(name);
        long last;
        boolean called;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT last_value, is_called FROM " + qualified)) {
            if (!result.next()) throw rejected("UNSAFE_TARGET_SEQUENCE");
            last = result.getLong(1);
            called = result.getBoolean(2);
        }
        long maximum;
        // table is one of the two internal constants above, never an input identifier.
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM public." + table)) {
            result.next();
            maximum = result.getLong(1);
        }
        if (last < maximum || (!called && last <= maximum) || last >= maxValue) {
            throw rejected("TARGET_SEQUENCE_BEHIND_OR_EXHAUSTED");
        }
        return new Sequence(qualified, maximum);
    }

    private static long allocate(Connection connection, Sequence sequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_catalog.nextval(?::regclass)")) {
            statement.setString(1, sequence.qualifiedName());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                long id = result.getLong(1);
                if (id <= sequence.maximum()) throw rejected("TARGET_SEQUENCE_COLLISION");
                return id;
            }
        }
    }

    private static void merge(Connection connection, PreparedInput input, Map<Long, Long> mappedOwners)
            throws SQLException {
        String namespace = input.snapshot().sourceNamespace();
        Map<Long, Long> owners = new HashMap<>(mappedOwners);
        for (var entry : new java.util.TreeMap<>(mappedOwners).entrySet()) {
            provenance(connection, namespace, "user", Long.toString(entry.getKey()), Long.toString(entry.getValue()));
        }
        Sequence userSequence = sequence(connection, "psych_user");
        for (long sourceOwner : new TreeSet<>(input.deletedOwners())) {
            long targetOwner = allocate(connection, userSequence);
            // Even the marker must not accidentally point to a current account.
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM public.psych_user WHERE id=?")) {
                statement.setLong(1, targetOwner);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) throw rejected("TARGET_SEQUENCE_COLLISION");
                }
            }
            owners.put(sourceOwner, targetOwner);
            provenance(connection, namespace, "deleted_owner", Long.toString(sourceOwner), Long.toString(targetOwner));
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.psych_conversation(id,title,owner_id,created_at,updated_at) VALUES (?,?,?,?,?)
                """)) {
            for (var conversation : input.snapshot().conversations()) {
                Long owner = owners.get(conversation.ownerId());
                if (owner == null) throw rejected("MISSING_OWNER_MAPPING");
                statement.setString(1, conversation.id());
                statement.setString(2, conversation.title());
                statement.setLong(3, owner);
                statement.setTimestamp(4, timestamp(conversation.createdAt()));
                statement.setTimestamp(5, timestamp(conversation.updatedAt()));
                statement.executeUpdate();
                provenance(connection, namespace, "conversation", conversation.id(), conversation.id());
            }
        }
        Map<String, Long> cursors = new HashMap<>();
        Sequence messageSequence = sequence(connection, "psych_chat_message");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.psych_chat_message(id,conversation_id,role,content,client_msg_id,created_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            for (var entry : input.messagePlans().entrySet()) {
                Map<Long, Long> allocations = new LinkedHashMap<>();
                for (var step : entry.getValue().steps()) {
                    long targetId = allocate(connection, messageSequence);
                    allocations.put(step.sourceMessageId(), targetId);
                    if (step.virtualMarker()) {
                        provenance(connection, namespace, "message_watermark", compound(entry.getKey(),
                                Long.toString(step.sourceMessageId())), Long.toString(targetId));
                    } else {
                        var message = step.message();
                        statement.setLong(1, targetId);
                        statement.setString(2, message.conversationId());
                        statement.setString(3, message.role());
                        statement.setString(4, message.content());
                        statement.setString(5, message.clientMsgId());
                        statement.setTimestamp(6, timestamp(message.createdAt()));
                        statement.executeUpdate();
                        provenance(connection, namespace, "message", Long.toString(message.id()), Long.toString(targetId));
                    }
                }
                cursors.put(entry.getKey(), entry.getValue().remapCursor(allocations));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.psych_conversation_memory(conversation_id,digest,covered_until_message_id,
                    covered_message_count,digest_chars,updated_at) VALUES (?,?,?,?,?,?)
                """)) {
            for (var memory : input.snapshot().memories()) {
                statement.setString(1, memory.conversationId());
                statement.setString(2, memory.digest());
                statement.setLong(3, cursors.get(memory.conversationId()));
                statement.setInt(4, memory.coveredMessageCount());
                statement.setInt(5, memory.digestChars());
                statement.setTimestamp(6, timestamp(memory.updatedAt()));
                statement.executeUpdate();
                provenance(connection, namespace, "memory", memory.conversationId(), memory.conversationId());
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.psych_chat_turn(conversation_id,client_msg_id,request_hash,status,answer,
                    effective_mode,fallback,updated_at) VALUES (?,?,?,?,?,?,?,?)
                """)) {
            for (var turn : input.snapshot().turns()) {
                statement.setString(1, turn.conversationId());
                statement.setString(2, turn.clientMsgId());
                statement.setString(3, turn.requestHash());
                statement.setString(4, "RUNNING".equals(turn.status()) ? "FAILED" : turn.status());
                statement.setString(5, turn.answer());
                statement.setString(6, turn.effectiveMode());
                statement.setBoolean(7, turn.fallback());
                statement.setTimestamp(8, timestamp(turn.updatedAt()));
                statement.executeUpdate();
                String key = compound(turn.conversationId(), turn.clientMsgId());
                provenance(connection, namespace, "turn", key, key);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.psych_conversation_tombstone(conversation_id,owner_id,deleted_at) VALUES (?,?,?)
                """)) {
            for (var tombstone : input.snapshot().tombstones()) {
                Long owner = owners.get(tombstone.ownerId());
                if (owner == null) throw rejected("MISSING_OWNER_MAPPING");
                statement.setString(1, tombstone.conversationId());
                statement.setLong(2, owner);
                statement.setTimestamp(3, timestamp(tombstone.deletedAt()));
                statement.executeUpdate();
                provenance(connection, namespace, "tombstone", tombstone.conversationId(), tombstone.conversationId());
            }
        }
    }

    private record Ledger(String snapshot, String mapping, String plan, String targetIdentity, int version) {}

    private static Ledger inspectLedger(Connection connection, String namespace) throws SQLException {
        if (!ledgerExists(connection)) return null;
        validateLedger(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_sha256,mapping_sha256,plan_sha256,target_identity_sha256,format_version
                FROM psych_transfer.imports WHERE source_namespace=?
                """)) {
            statement.setString(1, namespace);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Ledger(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getInt(5));
            }
        }
    }

    private static void requireMatchingLedger(Ledger ledger, TransferMergePlan plan) throws SQLException {
        if (!ledger.snapshot().equals(plan.snapshotSha256()) || !ledger.mapping().equals(plan.mappingSha256())
                || ledger.version() != plan.formatVersion()) throw rejected("SOURCE_NAMESPACE_ALREADY_IMPORTED");
        if (!ledger.targetIdentity().equals(plan.targetIdentitySha256()) || !ledger.plan().equals(plan.planSha256())) {
            throw rejected("PLAN_CHANGED");
        }
    }

    private static boolean ledgerExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname='psych_transfer'");
             ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private static void ensureLedger(Connection connection) throws SQLException {
        if (ledgerExists(connection)) {
            validateLedger(connection);
            return;
        }
        execute(connection, "CREATE SCHEMA psych_transfer");
        execute(connection, """
                CREATE TABLE psych_transfer.imports (
                    source_namespace TEXT PRIMARY KEY,
                    snapshot_sha256 TEXT NOT NULL,
                    mapping_sha256 TEXT NOT NULL,
                    plan_sha256 TEXT NOT NULL,
                    target_identity_sha256 TEXT NOT NULL,
                    format_version INTEGER NOT NULL,
                    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                CREATE TABLE psych_transfer.entity_map (
                    source_namespace TEXT NOT NULL REFERENCES psych_transfer.imports(source_namespace),
                    entity_kind TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    PRIMARY KEY(source_namespace,entity_kind,source_id),
                    UNIQUE(source_namespace,entity_kind,target_id)
                )
                """);
        validateLedger(connection);
    }

    /** Fail closed if an existing ledger can transform or redirect INSERTs. */
    private static void validateLedger(Connection connection) throws SQLException {
        validateLedgerTable(connection, "imports", List.of(
                new Column("source_namespace", "text", null), new Column("snapshot_sha256", "text", null),
                new Column("mapping_sha256", "text", null), new Column("plan_sha256", "text", null),
                new Column("target_identity_sha256", "text", null), new Column("format_version", "integer", null),
                new Column("imported_at", "timestamp with time zone", "CURRENT_TIMESTAMP")),
                Set.of("p:source_namespace"));
        validateLedgerTable(connection, "entity_map", List.of(
                new Column("source_namespace", "text", null), new Column("entity_kind", "text", null),
                new Column("source_id", "text", null), new Column("target_id", "text", null)),
                Set.of("p:source_namespace,entity_kind,source_id", "u:source_namespace,entity_kind,target_id",
                        "f:source_namespace"));
    }

    private record Column(String name, String type, String defaultExpression) {}

    private static void validateLedgerTable(Connection connection, String table, List<Column> expected,
                                             Set<String> expectedConstraints) throws SQLException {
        long relation;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.oid, c.relkind, c.relpersistence, c.relrowsecurity, c.relforcerowsecurity,
                    EXISTS(SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal),
                    EXISTS(SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class=c.oid),
                    EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='psych_transfer' AND c.relname=?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"r".equals(result.getString(2)) || !"p".equals(result.getString(3))
                        || result.getBoolean(4) || result.getBoolean(5) || result.getBoolean(6)
                        || result.getBoolean(7) || result.getBoolean(8)) throw rejected("INVALID_TRANSFER_LEDGER");
                relation = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod), a.attnotnull,
                    a.attgenerated, a.attidentity, pg_catalog.pg_get_expr(d.adbin,d.adrelid)
                FROM pg_catalog.pg_attribute a LEFT JOIN pg_catalog.pg_attrdef d
                    ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                WHERE a.attrelid=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum
                """)) {
            statement.setLong(1, relation);
            try (ResultSet result = statement.executeQuery()) {
                int index = 0;
                while (result.next()) {
                    if (index >= expected.size()) throw rejected("INVALID_TRANSFER_LEDGER");
                    Column column = expected.get(index++);
                    if (!column.name().equals(result.getString(1)) || !column.type().equals(result.getString(2))
                            || !result.getBoolean(3) || !result.getString(4).isEmpty()
                            || !result.getString(5).isEmpty()
                            || !java.util.Objects.equals(column.defaultExpression(), result.getString(6))) {
                        throw rejected("INVALID_TRANSFER_LEDGER");
                    }
                }
                if (index != expected.size()) throw rejected("INVALID_TRANSFER_LEDGER");
            }
        }
        Set<String> constraints = new HashSet<>();
        int indexes = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT con.contype, con.condeferrable, con.condeferred, con.convalidated,
                    (SELECT string_agg(a.attname, ',' ORDER BY key.ord)
                     FROM unnest(con.conkey) WITH ORDINALITY key(num,ord)
                     JOIN pg_catalog.pg_attribute a ON a.attrelid=con.conrelid AND a.attnum=key.num),
                    con.confrelid, con.confupdtype, con.confdeltype, con.confmatchtype,
                    (SELECT string_agg(a.attname, ',' ORDER BY key.ord)
                     FROM unnest(con.confkey) WITH ORDINALITY key(num,ord)
                     JOIN pg_catalog.pg_attribute a ON a.attrelid=con.confrelid AND a.attnum=key.num)
                FROM pg_catalog.pg_constraint con WHERE con.conrelid=?
                """)) {
            statement.setLong(1, relation);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String type = result.getString(1);
                    if (result.getBoolean(2) || result.getBoolean(3) || !result.getBoolean(4)
                            || !constraints.add(type + ":" + result.getString(5))) throw rejected("INVALID_TRANSFER_LEDGER");
                    if ("p".equals(type) || "u".equals(type)) indexes++;
                    if ("f".equals(type)) {
                        if (!"a".equals(result.getString(7)) || !"a".equals(result.getString(8))
                                || !"s".equals(result.getString(9)) || !"source_namespace".equals(result.getString(10))
                                || result.getLong(6) != ledgerImportsOid(connection)) {
                            throw rejected("INVALID_TRANSFER_LEDGER");
                        }
                    }
                }
            }
        }
        if (!constraints.equals(expectedConstraints)) throw rejected("INVALID_TRANSFER_LEDGER");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*), COALESCE(bool_and(i.indisvalid AND i.indisready AND i.indisunique
                    AND i.indexprs IS NULL AND i.indpred IS NULL), FALSE)
                FROM pg_catalog.pg_index i WHERE i.indrelid=?
                """)) {
            statement.setLong(1, relation);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != indexes || !result.getBoolean(2)) throw rejected("INVALID_TRANSFER_LEDGER");
            }
        }
    }

    private static long ledgerImportsOid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 'psych_transfer.imports'::regclass::oid")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void insertImport(Connection connection, TransferMergePlan plan) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO psych_transfer.imports(source_namespace,snapshot_sha256,mapping_sha256,
                    plan_sha256,target_identity_sha256,format_version) VALUES (?,?,?,?,?,?)
                """)) {
            statement.setString(1, plan.sourceNamespace());
            statement.setString(2, plan.snapshotSha256());
            statement.setString(3, plan.mappingSha256());
            statement.setString(4, plan.planSha256());
            statement.setString(5, plan.targetIdentitySha256());
            statement.setInt(6, plan.formatVersion());
            statement.executeUpdate();
        }
    }

    private static void provenance(Connection connection, String namespace, String kind, String source, String target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO psych_transfer.entity_map(source_namespace,entity_kind,source_id,target_id) VALUES (?,?,?,?)
                """)) {
            statement.setString(1, namespace);
            statement.setString(2, kind);
            statement.setString(3, source);
            statement.setString(4, target);
            statement.executeUpdate();
        }
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value));
    }

    private static String compound(String left, String right) {
        return left.length() + ":" + left + right;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String digest(List<String> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String value : values) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("TRANSFER_DIGEST_UNAVAILABLE");
        }
    }

    private static void timeouts(Connection connection) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '5s'");
        execute(connection, "SET LOCAL statement_timeout = '60s'");
        execute(connection, "SET LOCAL search_path = pg_catalog, public");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void requireDedicatedConnection(Connection connection) throws SQLException {
        if (connection == null || connection.isClosed() || !connection.getAutoCommit()) {
            throw rejected("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");
        }
    }

    private record ConnectionState(boolean readOnly, int isolation) {
        static ConnectionState of(Connection connection) throws SQLException {
            return new ConnectionState(connection.isReadOnly(), connection.getTransactionIsolation());
        }

        void restore(Connection connection) throws SQLException {
            // No commit can occur here: both successful and failed transactions were ended above.
            connection.setAutoCommit(true);
            connection.setReadOnly(readOnly);
            connection.setTransactionIsolation(isolation);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Do not attach a backend error containing row values to the public exception.
        }
    }

    private static SQLException rejected(String code) {
        return new MergeRejectedException(code);
    }

    private static SQLException safeSqlFailure(SQLException failure) {
        return failure instanceof MergeRejectedException ? failure : rejected("TRANSFER_DATABASE_OPERATION_FAILED");
    }

    private static final class MergeRejectedException extends SQLException {
        MergeRejectedException(String code) {
            super(code, "22000");
        }
    }
}
