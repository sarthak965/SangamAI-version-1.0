-- V5__create_conversation_nodes.sql
-- The fundamental unit of the conversation tree.
-- Every AI response — root or child — is a ConversationNode.

CREATE TABLE conversation_nodes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL
        REFERENCES sessions(id) ON DELETE CASCADE,

    -- null means this is the root node of the session
    parent_id UUID
        REFERENCES conversation_nodes(id) ON DELETE CASCADE,

    -- which paragraph of the parent node triggered this node
    -- null for the root node
    paragraph_id UUID,

    -- 0 = root, 1 = first child, 2 = grandchild, max 3
    depth INT NOT NULL DEFAULT 0,

    -- the question that caused this node (null for root)
    question TEXT,

    asked_by UUID REFERENCES users(id) ON DELETE SET NULL,

    -- the complete AI response for this node
    -- built up token by token during streaming
    full_content TEXT NOT NULL DEFAULT '',

    -- streaming = AI is currently generating
    -- complete  = AI finished, paragraphs are saved
    status VARCHAR(20) NOT NULL DEFAULT 'streaming'
        CHECK (status IN ('streaming', 'complete')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conv_nodes_session_id ON conversation_nodes(session_id);
CREATE INDEX idx_conv_nodes_parent_id ON conversation_nodes(parent_id);