-- DSE ERP v11.0.60: Live Multi-User MS Teams / Messenger Chat, Presence & Attachments
-- Supports internal company DMs, group channels, Admin <-> Super Admin support, live presence, and file attachments.

CREATE TABLE IF NOT EXISTS chat_presence (
    user_id            INTEGER PRIMARY KEY,
    status             VARCHAR(24) NOT NULL DEFAULT 'ONLINE',
    custom_note        VARCHAR(160),
    last_heartbeat_utc VARCHAR(64),
    updated_utc        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS chat_channel (
    id           BIGSERIAL PRIMARY KEY,
    channel_type VARCHAR(24) NOT NULL,
    channel_key  VARCHAR(160) UNIQUE,
    name         VARCHAR(120) NOT NULL,
    created_by   INTEGER,
    created_utc  VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_channel_member (
    id                   BIGSERIAL PRIMARY KEY,
    channel_id           BIGINT NOT NULL REFERENCES chat_channel(id) ON DELETE CASCADE,
    user_id              INTEGER NOT NULL,
    member_role          VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
    last_read_message_id BIGINT NOT NULL DEFAULT 0,
    joined_utc           VARCHAR(64) NOT NULL,
    CONSTRAINT uq_chat_channel_member UNIQUE (channel_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_member_user ON chat_channel_member(user_id, channel_id);

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGSERIAL PRIMARY KEY,
    channel_id      BIGINT NOT NULL REFERENCES chat_channel(id) ON DELETE CASCADE,
    sender_id       INTEGER NOT NULL,
    sender_username VARCHAR(100) NOT NULL,
    sender_name     VARCHAR(160) NOT NULL,
    sender_role     VARCHAR(64),
    body            TEXT,
    reply_to_id     BIGINT,
    reply_preview   VARCHAR(240),
    linked_doc_type VARCHAR(48),
    linked_doc_no   VARCHAR(80),
    reactions_json  TEXT,
    created_utc     VARCHAR(64) NOT NULL,
    edited_utc      VARCHAR(64),
    deleted         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_chat_message_channel_id ON chat_message(channel_id, id);

CREATE TABLE IF NOT EXISTS chat_attachment (
    id           BIGSERIAL PRIMARY KEY,
    message_id   BIGINT NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    channel_id   BIGINT NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(160),
    file_size    BIGINT NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    sha256       VARCHAR(64),
    uploaded_by  INTEGER NOT NULL,
    uploaded_utc VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_attachment_msg ON chat_attachment(message_id);
