package com.company.webhooks.delivery.dto;

import com.company.webhooks.delivery.Delivery;
import com.company.webhooks.delivery.DeliveryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeliveryResponse(
        UUID id,
        UUID eventId,
        UUID endpointId,
        String tenantId,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Integer lastResponseCode,
        String lastResponseSnippet,
        Instant createdAt,
        Instant updatedAt,
        List<DeliveryAttemptResponse> attempts
) {
    public static DeliveryResponse fromEntity(Delivery delivery, List<DeliveryAttemptResponse> attempts) {
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getEvent().getId(),
                delivery.getEndpoint().getId(),
                delivery.getTenantId(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastResponseCode(),
                delivery.getLastResponseSnippet(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt(),
                attempts
        );
    }

    public static DeliveryResponse fromEntity(Delivery delivery) {
        return fromEntity(delivery, null);
    }
}
