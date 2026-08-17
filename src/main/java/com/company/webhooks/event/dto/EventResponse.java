package com.company.webhooks.event.dto;

import com.company.webhooks.event.Event;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        UUID id,
        String tenantId,
        String eventId,
        String type,
        String status,
        int deliveriesCount,
        Instant createdAt
) {
    public static EventResponse fromEntity(Event event, String status, int deliveriesCount) {
        return new EventResponse(
                event.getId(),
                event.getTenantId(),
                event.getEventIdExternal(),
                event.getType(),
                status,
                deliveriesCount,
                event.getCreatedAt()
        );
    }
}
