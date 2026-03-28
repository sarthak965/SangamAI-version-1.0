package com.sangam.ai.ai;

/**
 * Represents one message in the conversation history sent to the AI.
 * Role is either "system", "user", or "assistant".
 */
public record AiMessage(String role, String content) {

    public static AiMessage system(String content) {
        return new AiMessage("system", content);
    }

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }
}