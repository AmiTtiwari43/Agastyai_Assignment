package com.company.webhooks.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record IngestEventRequest(
        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "type is required")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "type must contain only alphanumeric characters, dots, underscores, or hyphens")
        String type,

        @NotNull(message = "payload is required")
        Map<String, Object> payload
) {}
