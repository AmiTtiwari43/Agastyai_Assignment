package com.company.webhooks.delivery.dto;

import com.company.webhooks.deliveryattempt.DeliveryAttempt;
import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id,
        int attemptNumber,
        Integer responseCode,
        long latencyMs,
        String error,
        Instant createdAt
) {
    public static DeliveryAttemptResponse fromEntity(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getResponseCode(),
                attempt.getLatencyMs(),
                attempt.getError(),
                attempt.getCreatedAt()
        );
    }
}
