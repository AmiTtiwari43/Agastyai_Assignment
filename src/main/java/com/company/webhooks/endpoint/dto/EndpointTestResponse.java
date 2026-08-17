package com.company.webhooks.endpoint.dto;

public record EndpointTestResponse(
        boolean reachable,
        Integer statusCode,
        long latencyMs,
        String message,
        String responseSnippet
) {}
