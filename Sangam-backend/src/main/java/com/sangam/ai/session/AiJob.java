package com.sangam.ai.session;

import java.util.UUID;

/**
 * Represents one unit of AI work to be processed by the worker.
 *
 * This object is serialized to JSON and stored in Redis.
 * When the worker picks it up, it has everything it needs
 * to process the job — no context from the original HTTP
 * request is needed or available.
 *
 * Keep this class simple and serializable — only primitives,
 * Strings, and UUIDs. No JPA entities, no Spring beans.
 */
public record AiJob(

        // The node we already created and need to fill with AI content
        UUID nodeId,

        // Which session this belongs to (needed for root context assembly)
        UUID sessionId,

        // The question being asked
        String question,

        // Who asked it
        UUID askedByUserId,

        // ROOT = top-level question in the session
        // PARAGRAPH = follow-up on a specific paragraph
        JobType type,

        // Only set for PARAGRAPH jobs
        // null for ROOT jobs
        UUID parentNodeId,
        UUID paragraphId

) {
    public enum JobType {
        ROOT,
        PARAGRAPH
    }

    // Factory method for root level jobs
    public static AiJob rootJob(UUID nodeId, UUID sessionId,
                                String question, UUID askedByUserId) {
        return new AiJob(nodeId, sessionId, question,
                askedByUserId, JobType.ROOT, null, null);
    }

    // Factory method for paragraph level jobs
    public static AiJob paragraphJob(UUID nodeId, UUID sessionId,
                                     String question, UUID askedByUserId,
                                     UUID parentNodeId, UUID paragraphId) {
        return new AiJob(nodeId, sessionId, question,
                askedByUserId, JobType.PARAGRAPH, parentNodeId, paragraphId);
    }
}