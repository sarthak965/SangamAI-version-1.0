package com.sangam.ai.environment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEnvironmentRequest(
        @NotBlank(message = "Environment name is required")
        @Size(max = 100)
        String name,

        @Size(max = 500)
        String description
) {}