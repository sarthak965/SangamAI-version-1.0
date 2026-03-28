-- V6__create_paragraphs.sql
-- A paragraph is a completed segment of a ConversationNode's response.
-- Paragraphs are saved progressively as the AI streams —
-- paragraph 1 is saved and interactive before paragraph 2 is written.

CREATE TABLE paragraphs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    node_id UUID NOT NULL
        REFERENCES conversation_nodes(id) ON DELETE CASCADE,

    -- position within the node (0, 1, 2...)
    index INT NOT NULL,

    content TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_paragraphs_node_id ON paragraphs(node_id);