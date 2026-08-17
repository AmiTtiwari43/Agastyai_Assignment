package com.company.webhooks.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String correlationId,
        Map<String, String> validationErrors
) {
    public ErrorResponse(int status, String error, String message, String correlationId) {
        this(Instant.now(), status, error, message, correlationId, null);
    }

    public ErrorResponse(int status, String error, String message, String correlationId, Map<String, String> validationErrors) {
        this(Instant.now(), status, error, message, correlationId, validationErrors);
    }
}
