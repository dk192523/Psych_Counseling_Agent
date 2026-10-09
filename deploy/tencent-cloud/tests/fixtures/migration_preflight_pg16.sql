-- Synthetic CI database only. Mirrors the PostgreSQL initializers in
-- ConversationHistoryService, UserRepository and ChatTurnService, independently
-- of migration-preflight.py's COLUMN_CONTRACT. Never load this into project data.
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
CREATE EXTENSION vector WITH SCHEMA public;

CREATE TABLE psych_conversation (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE psych_chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES psych_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    client_msg_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE psych_chat_message ADD COLUMN IF NOT EXISTS client_msg_id VARCHAR(64);
CREATE TABLE psych_conversation_memory (
    conversation_id VARCHAR(64) PRIMARY KEY REFERENCES psych_conversation(id) ON DELETE CASCADE,
    digest TEXT NOT NULL DEFAULT '',
    covered_until_message_id BIGINT NOT NULL DEFAULT 0,
    covered_message_count INT NOT NULL DEFAULT 0,
    digest_chars INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_psych_chat_message_conversation_id_id ON psych_chat_message (conversation_id, id);
CREATE UNIQUE INDEX uk_psych_chat_message_client_msg ON psych_chat_message (conversation_id, client_msg_id);
CREATE INDEX idx_psych_conversation_updated_at ON psych_conversation (updated_at DESC);
CREATE TABLE psych_conversation_tombstone (
    conversation_id VARCHAR(64) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE psych_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ,
    disabled_at TIMESTAMPTZ,
    disabled_reason VARCHAR(200)
);
ALTER TABLE psych_conversation ADD COLUMN IF NOT EXISTS owner_id BIGINT REFERENCES psych_user(id);
CREATE INDEX idx_psych_conversation_owner_updated ON psych_conversation (owner_id, updated_at DESC);
CREATE TABLE psych_chat_turn (
    conversation_id VARCHAR(64) NOT NULL REFERENCES psych_conversation(id) ON DELETE CASCADE,
    client_msg_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    answer TEXT NOT NULL DEFAULT '',
    effective_mode VARCHAR(16) NOT NULL DEFAULT 'standard',
    fallback BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, client_msg_id)
);
CREATE UNIQUE INDEX uk_psych_running_turn ON psych_chat_turn(conversation_id) WHERE status = 'RUNNING';
CREATE INDEX idx_psych_turn_expiry ON psych_chat_turn(updated_at) WHERE status <> 'RUNNING';

-- Dependency-owned Spring AI pgvector table; configured dimensions are 384.
CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSON,
    embedding vector(384)
);
CREATE INDEX spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);

INSERT INTO psych_user (username, password_hash) VALUES ('ci_synthetic_user', 'ci_not_a_real_password_hash');
INSERT INTO psych_conversation (id, title, owner_id) VALUES ('ci_conversation', 'Synthetic CI conversation', 1);
INSERT INTO psych_chat_message (conversation_id, role, content, client_msg_id)
VALUES ('ci_conversation', 'user', 'Synthetic CI message', 'ci_message');
-- A retention-deleted watermark row is valid and must not become a blocker.
INSERT INTO psych_conversation_memory (conversation_id, digest, covered_until_message_id, covered_message_count, digest_chars)
VALUES ('ci_conversation', 'Synthetic CI digest', 999, 1, 19);
INSERT INTO psych_chat_turn (conversation_id, client_msg_id, request_hash, status)
VALUES ('ci_conversation', 'ci_turn', 'ci_request_hash', 'RUNNING');
-- Deleted users can legitimately leave tombstones without foreign keys.
INSERT INTO psych_conversation_tombstone (conversation_id, owner_id) VALUES ('ci_deleted_conversation', 999);
INSERT INTO vector_store (id, content, metadata, embedding)
VALUES ('00000000-0000-4000-8000-000000000001', 'Synthetic CI document', '{}',
        ('[' || array_to_string(array_fill(0.01::real, ARRAY[384]), ',') || ']')::vector);
