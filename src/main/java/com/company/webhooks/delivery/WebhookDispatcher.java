package com.company.webhooks.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final HttpClient httpClient;
    private final WebhookSigner webhookSigner;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public record DispatchResult(
            boolean success,
            Integer statusCode,
            long latencyMs,
            String responseSnippet,
            String errorMessage
    ) {}

    public WebhookDispatcher(
            WebhookSigner webhookSigner,
            ObjectMapper objectMapper,
            @Value("${webhooks.worker.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${webhooks.worker.read-timeout-ms:10000}") long readTimeoutMs) {
        this.webhookSigner = webhookSigner;
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofMillis(readTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public DispatchResult dispatch(String url, String secret, Map<String, Object> payload, String correlationId) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. Serialize payload to raw bytes
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String signature = webhookSigner.computeSignature(payloadBytes, secret);

            // 2. Build HTTP request with timeouts and signing headers
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Correlation-Id", correlationId != null ? correlationId : "")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payloadBytes))
                    .build();

            // 3. Send HTTP request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = System.currentTimeMillis() - startTime;

            int statusCode = response.statusCode();
            boolean isSuccess = statusCode >= 200 && statusCode < 300;
            String snippet = truncateSnippet(response.body(), 500);

            if (isSuccess) {
                return new DispatchResult(true, statusCode, latencyMs, snippet, null);
            } else {
                return new DispatchResult(false, statusCode, latencyMs, snippet, "Received non-2xx status code: " + statusCode);
            }

        } catch (HttpTimeoutException te) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("Webhook dispatch timed out [url={}, timeout={}]", url, requestTimeout);
            return new DispatchResult(false, null, latencyMs, null, "Request timed out after " + requestTimeout.toMillis() + "ms");
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("Webhook dispatch failed [url={}, error={}]", url, ex.getMessage());
            return new DispatchResult(false, null, latencyMs, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private String truncateSnippet(String body, int maxLength) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...[truncated]";
    }
}
