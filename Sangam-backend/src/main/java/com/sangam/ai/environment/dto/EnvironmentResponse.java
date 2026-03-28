package com.sangam.ai.environment.dto;

import com.sangam.ai.environment.Environment;
import java.time.Instant;
import java.util.UUID;

public record EnvironmentResponse(
        UUID id,
        String name,
        String description,
        String inviteCode,
        String hostUsername,
        Instant createdAt
) {
    // A static factory method that converts the Entity into a DTO.
    // We NEVER return the raw entity from a controller — the entity
    // is an internal object tied to JPA. The DTO is the public contract.
    public static EnvironmentResponse from(Environment env) {
        return new EnvironmentResponse(
                env.getId(),
                env.getName(),
                env.getDescription(),
                env.getInviteCode(),
                env.getHost().getUsername(),
                env.getCreatedAt()
        );
    }
}