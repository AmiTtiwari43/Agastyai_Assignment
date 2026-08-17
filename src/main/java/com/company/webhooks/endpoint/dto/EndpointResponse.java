package com.company.webhooks.endpoint.dto;

import com.company.webhooks.endpoint.Endpoint;
import com.company.webhooks.endpoint.EndpointStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndpointResponse(
        UUID id,
        String tenantId,
        String url,
        String secret,
        List<String> subscribedEventTypes,
        EndpointStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static EndpointResponse fromEntity(Endpoint endpoint) {
        return new EndpointResponse(
                endpoint.getId(),
                endpoint.getTenantId(),
                endpoint.getUrl(),
                endpoint.getSecret(),
                endpoint.getSubscribedEventTypes(),
                endpoint.getStatus(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt()
        );
    }
}
