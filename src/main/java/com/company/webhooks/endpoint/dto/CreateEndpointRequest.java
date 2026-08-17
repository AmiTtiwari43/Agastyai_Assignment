package com.company.webhooks.endpoint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateEndpointRequest(
        @NotBlank(message = "URL is required")
        String url,

        @NotEmpty(message = "Subscribed event types cannot be empty")
        List<String> subscribedEventTypes
) {}
