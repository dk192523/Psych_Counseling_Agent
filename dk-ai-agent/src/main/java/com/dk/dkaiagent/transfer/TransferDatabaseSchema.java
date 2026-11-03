package com.dk.dkaiagent.transfer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catalog-only contract for the six public business tables. Never initializes a schema. */
public final class TransferDatabaseSchema {
    public static final String SCHEMA_VERSION = "psych-business-v1";
    public static final int POSTGRES_MAJOR_VERSION = 16;
    public static final String VALIDATION_ERROR = "PostgreSQL business schema is unsupported or unavailable.";
    public static final List<String> BUSINESS_TABLES = List.of("psych_user", "psych_conversation",
            "psych_chat_message", "psych_conversation_memory", "psych_chat_turn", "psych_conversation_tombstone");

    private TransferDatabaseSchema() { }

    /** Does not commit, roll back, or modify any connection/transaction setting. */
    public static void validatePostgres(Connection connection) throws SQLException {
        try {
            if (connection == null || connection.isClosed()) throw invalid();
            DatabaseMetaData metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName())
                    || metadata.getDatabaseMajorVersion() != POSTGRES_MAJOR_VERSION) throw invalid();
            for (String query : contractQueries()) {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    statement.setQueryTimeout(30);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next() || rows.getLong(1) != 0 || rows.wasNull() || rows.next()) throw invalid();
                    }
                }
            }
        } catch (SQLException | RuntimeException error) {
            // JDBC diagnostics can contain credentials, object names and private values.
            throw invalid();
        }
    }

    private static SQLException invalid() { return new SQLException(VALIDATION_ERROR, "P0001"); }

    static List<String> contractQueries() {
        return Contract.QUERIES;
    }

    private static final class Contract {
        private static final List<String> QUERIES = buildQueries();
    }

    private record Column(String type, boolean nullable, String defaultValue) { }

    private static List<String> buildQueries() {
        Map<String, Map<String, Column>> columns = columns();
        List<String> queries = new ArrayList<>();
        String tableNames = BUSINESS_TABLES.stream().map(TransferDatabaseSchema::literal)
                .collect(java.util.stream.Collectors.joining(","));
        String relations = BUSINESS_TABLES.stream().map(t -> "to_regclass(" + literal("public." + t) + ")")
                .collect(java.util.stream.Collectors.joining(","));
        String tableRows = BUSINESS_TABLES.stream().map(t -> "(" + literal(t) + ")")
                .collect(java.util.stream.Collectors.joining(","));
        queries.add(check("version", """
                SELECT CASE WHEN current_setting('server_version_num')::integer / 10000 = 16 THEN 0 ELSE 1 END
                """));
        queries.add(check("tables", """
                WITH required(tab) AS (VALUES %s)
                SELECT count(*) FROM required r LEFT JOIN pg_namespace n ON n.nspname='public'
                LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=r.tab
                LEFT JOIN pg_am am ON am.oid=c.relam
                WHERE c.oid IS NULL OR c.relkind<>'r' OR c.relpersistence<>'p'
                    OR am.amname IS DISTINCT FROM 'heap' OR c.relispartition
                    OR c.relrowsecurity OR c.relforcerowsecurity
                    OR EXISTS (SELECT 1 FROM pg_inherits x WHERE x.inhrelid=c.oid OR x.inhparent=c.oid)
                    OR EXISTS (SELECT 1 FROM pg_trigger x WHERE x.tgrelid=c.oid AND NOT x.tgisinternal)
                    OR EXISTS (SELECT 1 FROM pg_policy x WHERE x.polrelid=c.oid)
                    OR EXISTS (SELECT 1 FROM pg_rewrite x WHERE x.ev_class=c.oid)
                """.formatted(tableRows)));
        queries.add(check("unexpected-business-relations", """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%%'
                    AND n.nspname NOT LIKE 'pg_temp_%%' AND c.relkind IN ('r','p','v','m','f') AND (
                        (c.relname IN (%s) AND n.nspname<>'public') OR
                        (n.nspname='public' AND (c.relname LIKE 'psych\\_%%' ESCAPE '\\'
                            OR c.relname='flyway_schema_history') AND c.relname NOT IN (%s)))
                """.formatted(tableNames, tableNames)));

        List<String> columnRows = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();
        columns.forEach((table, fields) -> fields.forEach((name, column) -> {
            columnRows.add("(" + literal(table) + "," + literal(name) + "," + literal(column.type()) + ","
                    + column.nullable() + "," + (column.defaultValue() == null ? "NULL" : literal(column.defaultValue())) + ")");
            columnNames.add("(" + literal(table) + "," + literal(name) + ")");
        }));
        String columnCte = """
                WITH required(tab,col,typ,nullable,def) AS (VALUES %s), actual AS (
                    SELECT r.*, a.attnum, a.attnotnull, a.attidentity, a.attgenerated,
                        format_type(a.atttypid,a.atttypmod) AS actual_type,
                        d.oid AS default_oid, pg_get_expr(d.adbin,d.adrelid) AS actual_default,
                        to_regclass(pg_get_serial_sequence('public.'||r.tab,r.col)) AS owned_sequence
                    FROM required r
                    LEFT JOIN pg_attribute a ON a.attrelid=to_regclass('public.'||r.tab)
                        AND a.attname=r.col AND a.attnum>0 AND NOT a.attisdropped
                    LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum)
                """.formatted(String.join(",", columnRows));
        queries.add(check("unexpected-columns", """
                WITH required(tab,col) AS (VALUES %s)
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN (%s) AND a.attnum>0 AND NOT a.attisdropped
                    AND NOT EXISTS (SELECT 1 FROM required r WHERE r.tab=c.relname AND r.col=a.attname)
                """.formatted(String.join(",", columnNames), tableNames)));
        queries.add(check("column-types-and-nullability", columnCte + """
                SELECT count(*) FROM actual WHERE actual_type IS DISTINCT FROM typ
                    OR attnotnull IS DISTINCT FROM NOT nullable
                """));
        queries.add(check("column-defaults", columnCte + """
                SELECT count(*) FROM actual r WHERE attidentity IS DISTINCT FROM ''
                    OR attgenerated IS DISTINCT FROM '' OR NOT (
                        CASE WHEN def='serial' THEN owned_sequence IS NOT NULL
                            AND actual_default IS NOT DISTINCT FROM
                                ('nextval('||quote_literal(owned_sequence::text)||'::regclass)')
                            AND EXISTS (SELECT 1 FROM pg_depend dep
                                WHERE dep.classid='pg_attrdef'::regclass AND dep.objid=r.default_oid
                                    AND dep.refclassid='pg_class'::regclass AND dep.refobjid=r.owned_sequence)
                        ELSE actual_default IS NOT DISTINCT FROM def END)
                """));

        Map<String, List<String>> primary = new LinkedHashMap<>();
        primary.put("psych_user", List.of("id"));
        primary.put("psych_conversation", List.of("id"));
        primary.put("psych_chat_message", List.of("id"));
        primary.put("psych_conversation_memory", List.of("conversation_id"));
        primary.put("psych_chat_turn", List.of("conversation_id", "client_msg_id"));
        primary.put("psych_conversation_tombstone", List.of("conversation_id"));
        List<String> primaryRows = new ArrayList<>();
        primary.forEach((table, keys) -> primaryRows.add("(" + literal(table) + "," + textArray(keys) + ")"));
        queries.add(check("primary-keys", """
                WITH required(tab,cols) AS (VALUES %s)
                SELECT count(*) FROM required r WHERE (SELECT count(*) FROM pg_constraint k
                    JOIN pg_index i ON i.indexrelid=k.conindid
                    WHERE k.conrelid=to_regclass('public.'||r.tab) AND k.contype='p'
                        AND k.convalidated AND NOT k.condeferrable AND NOT k.condeferred
                        AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                        AND i.indisunique AND i.indisprimary AND i.indexprs IS NULL AND i.indpred IS NULL
                        AND i.indnatts=i.indnkeyatts
                        AND ARRAY(SELECT a.attname::text FROM unnest(k.conkey) WITH ORDINALITY x(num,ord)
                            JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=x.num
                            ORDER BY x.ord)=r.cols)<>1
                """.formatted(String.join(",", primaryRows))));
        String fkCte = """
                WITH required(src,col,dst,dstcol,del) AS (VALUES
                    ('psych_conversation','owner_id','psych_user','id','a'),
                    ('psych_chat_message','conversation_id','psych_conversation','id','c'),
                    ('psych_conversation_memory','conversation_id','psych_conversation','id','c'),
                    ('psych_chat_turn','conversation_id','psych_conversation','id','c')), actual AS (
                    SELECT k.*, ARRAY(SELECT a.attname::text FROM unnest(k.conkey) WITH ORDINALITY x(num,ord)
                        JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=x.num ORDER BY x.ord) AS src_cols,
                    ARRAY(SELECT a.attname::text FROM unnest(k.confkey) WITH ORDINALITY x(num,ord)
                        JOIN pg_attribute a ON a.attrelid=k.confrelid AND a.attnum=x.num ORDER BY x.ord) AS dst_cols
                    FROM pg_constraint k WHERE k.contype='f' AND k.conrelid IN (%s))
                """.formatted(relations);
        String fkMatch = """
                k.conrelid=to_regclass('public.'||r.src) AND k.confrelid=to_regclass('public.'||r.dst)
                    AND k.src_cols=ARRAY[r.col] AND k.dst_cols=ARRAY[r.dstcol]
                    AND k.confdeltype::text=r.del AND k.confupdtype='a' AND k.confmatchtype='s'
                    AND k.convalidated AND NOT k.condeferrable AND NOT k.condeferred
                """;
        queries.add(check("foreign-keys", fkCte + "SELECT count(*) FROM required r WHERE (SELECT count(*) FROM actual k WHERE "
                + fkMatch + ")<>1"));
        queries.add(check("unexpected-foreign-keys", fkCte
                + "SELECT count(*) FROM actual k WHERE NOT EXISTS (SELECT 1 FROM required r WHERE " + fkMatch + ")"));
        queries.add(check("unexpected-constraints", """
                SELECT count(*) FROM pg_constraint k WHERE k.conrelid IN (%s) AND k.contype NOT IN ('p','u','f')
                """.formatted(relations)));
        String indexCte = """
                WITH required(tab,cols,predicate) AS (VALUES
                    ('psych_user',ARRAY['username']::text[],NULL::text),
                    ('psych_chat_message',ARRAY['conversation_id','client_msg_id']::text[],NULL::text),
                    ('psych_chat_turn',ARRAY['conversation_id']::text[],'((status)::text = ''RUNNING''::text)'))
                """;
        String indexMatch = """
                i.indrelid=to_regclass('public.'||r.tab) AND i.indisunique
                    AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                    AND NOT i.indnullsnotdistinct AND am.amname='btree'
                    AND i.indexprs IS NULL AND i.indnatts=i.indnkeyatts
                    AND ARRAY(SELECT a.attname::text FROM unnest(i.indkey) WITH ORDINALITY x(num,ord)
                        JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=x.num ORDER BY x.ord)=r.cols
                    AND NOT EXISTS (SELECT 1 FROM unnest(i.indclass,i.indcollation,i.indoption)
                        WITH ORDINALITY x(opclass,collation,options,ord)
                        JOIN pg_opclass op ON op.oid=x.opclass
                        JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attname=r.cols[x.ord::integer]
                        WHERE NOT op.opcdefault OR x.collation<>a.attcollation OR x.options<>0)
                    AND pg_get_expr(i.indpred,i.indrelid) IS NOT DISTINCT FROM r.predicate
                """;
        queries.add(check("critical-unique-indexes", indexCte + """
                SELECT count(*) FROM required r WHERE NOT EXISTS (
                    SELECT 1 FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid
                    JOIN pg_am am ON am.oid=idx.relam WHERE
                """ + indexMatch + ")"));
        queries.add(check("unexpected-unique-indexes", indexCte + """
                SELECT count(*) FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_am am ON am.oid=idx.relam WHERE i.indrelid IN (%s)
                    AND i.indisunique AND NOT i.indisprimary AND NOT EXISTS (SELECT 1 FROM required r WHERE
                """.formatted(relations) + indexMatch + ")"));
        return List.copyOf(queries);
    }

    private static String check(String name, String sql) { return "/* transfer-schema: " + name + " */\n" + sql; }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String textArray(List<String> values) {
        return "ARRAY[" + values.stream().map(TransferDatabaseSchema::literal)
                .collect(java.util.stream.Collectors.joining(",")) + "]::text[]";
    }

    private static Map<String, Map<String, Column>> columns() {
        Map<String, Map<String, Column>> tables = new LinkedHashMap<>();
        Map<String, Column> user = table(tables, "psych_user");
        column(user, "id", "bigint", false, "serial");
        column(user, "username", "character varying(64)", false, null);
        column(user, "password_hash", "character varying(100)", false, null);
        column(user, "role", "character varying(16)", false, "'USER'::character varying");
        column(user, "status", "character varying(16)", false, "'ACTIVE'::character varying");
        column(user, "created_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        column(user, "updated_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        column(user, "last_login_at", "timestamp with time zone", true, null);
        column(user, "disabled_at", "timestamp with time zone", true, null);
        column(user, "disabled_reason", "character varying(200)", true, null);
        Map<String, Column> conversation = table(tables, "psych_conversation");
        column(conversation, "id", "character varying(64)", false, null);
        column(conversation, "title", "character varying(120)", false, null);
        column(conversation, "owner_id", "bigint", true, null);
        column(conversation, "created_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        column(conversation, "updated_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        Map<String, Column> message = table(tables, "psych_chat_message");
        column(message, "id", "bigint", false, "serial");
        column(message, "conversation_id", "character varying(64)", false, null);
        column(message, "role", "character varying(16)", false, null);
        column(message, "content", "text", false, null);
        column(message, "client_msg_id", "character varying(64)", true, null);
        column(message, "created_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        Map<String, Column> memory = table(tables, "psych_conversation_memory");
        column(memory, "conversation_id", "character varying(64)", false, null);
        column(memory, "digest", "text", false, "''::text");
        column(memory, "covered_until_message_id", "bigint", false, "0");
        column(memory, "covered_message_count", "integer", false, "0");
        column(memory, "digest_chars", "integer", false, "0");
        column(memory, "updated_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        Map<String, Column> turn = table(tables, "psych_chat_turn");
        column(turn, "conversation_id", "character varying(64)", false, null);
        column(turn, "client_msg_id", "character varying(64)", false, null);
        column(turn, "request_hash", "character varying(64)", false, null);
        column(turn, "status", "character varying(16)", false, null);
        column(turn, "answer", "text", false, "''::text");
        column(turn, "effective_mode", "character varying(16)", false, "'standard'::character varying");
        column(turn, "fallback", "boolean", false, "false");
        column(turn, "updated_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        Map<String, Column> tombstone = table(tables, "psych_conversation_tombstone");
        column(tombstone, "conversation_id", "character varying(64)", false, null);
        column(tombstone, "owner_id", "bigint", false, null);
        column(tombstone, "deleted_at", "timestamp with time zone", false, "CURRENT_TIMESTAMP");
        return tables;
    }

    private static Map<String, Column> table(Map<String, Map<String, Column>> tables, String name) {
        Map<String, Column> fields = new LinkedHashMap<>();
        tables.put(name, fields);
        return fields;
    }

    private static void column(Map<String, Column> fields, String name, String type, boolean nullable, String defaultValue) {
        fields.put(name, new Column(type, nullable, defaultValue));
    }
}
