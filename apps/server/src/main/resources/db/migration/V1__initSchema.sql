CREATE TABLE sys_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE sys_personalization (
    doc_type VARCHAR(32) PRIMARY KEY,
    content TEXT NOT NULL,
    revision VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ai_language_provider (
    provider_id VARCHAR(64) PRIMARY KEY,
    label VARCHAR(128),
    api_url TEXT,
    protocol VARCHAR(32),
    api_key TEXT,
    models JSONB,
    enabled BOOLEAN,
    updated_at TIMESTAMPTZ
);

CREATE TABLE ai_media_provider (
    provider_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(128),
    label VARCHAR(128),
    source_code TEXT,
    revision VARCHAR(64),
    config JSONB,
    models JSONB,
    enabled BOOLEAN,
    updated_at TIMESTAMPTZ
);

CREATE TABLE skill_meta (
    skill_name VARCHAR(128) PRIMARY KEY,
    description TEXT,
    enabled BOOLEAN,
    file_order JSONB,
    updated_at TIMESTAMPTZ
);

CREATE TABLE workspace_project (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64),
    directory TEXT UNIQUE,
    last_opened_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ
);
