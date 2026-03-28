package com.sangam.ai.environment.dto;

public record UpdatePermissionRequest(
        String username,
        boolean canInteractWithAi
) {}