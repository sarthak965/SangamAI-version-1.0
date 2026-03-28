-- V4__create_sessions.sql
-- A session is one AI conversation inside an environment.
-- One environment can have many sessions over time.

CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    environment_id UUID NOT NULL
        REFERENCES environments(id) ON DELETE CASCADE,

    -- Who started this session
    created_by UUID NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    title VARCHAR(255),

    -- open   = members can ask questions
    -- closed = host has ended the session, read-only
    status VARCHAR(20) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'closed')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_environment_id ON sessions(environment_id);