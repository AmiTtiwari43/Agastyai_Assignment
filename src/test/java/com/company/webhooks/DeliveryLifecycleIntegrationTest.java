package com.company.webhooks;

import com.company.webhooks.delivery.Delivery;
import com.company.webhooks.delivery.DeliveryExecutionService;
import com.company.webhooks.delivery.DeliveryRepository;
import com.company.webhooks.delivery.DeliveryStatus;
import com.company.webhooks.delivery.dto.DeliveryResponse;
import com.company.webhooks.deliveryattempt.DeliveryAttempt;
import com.company.webhooks.deliveryattempt.DeliveryAttemptRepository;
import com.company.webhooks.endpoint.EndpointRepository;
import com.company.webhooks.endpoint.dto.CreateEndpointRequest;
import com.company.webhooks.endpoint.dto.EndpointResponse;
import com.company.webhooks.event.EventRepository;
import com.company.webhooks.event.dto.EventResponse;
import com.company.webhooks.event.dto.IngestEventRequest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private DeliveryExecutionService deliveryExecutionService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @BeforeEach
    void cleanDatabase() {
        deliveryAttemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        eventRepository.deleteAll();
        endpointRepository.deleteAll();
    }

    private HttpHeaders createHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Delivery Lifecycle: Successful HTTP 200 delivery updates status to DELIVERED and records attempt")
    void testSuccessfulDeliveryLifecycle() {
        String tenantId = "tenant-lifecycle-" + UUID.randomUUID();

        // Stub WireMock endpoint
        wireMockServer.stubFor(post(urlEqualTo("/hook/success"))
                .withHeader("X-Webhook-Signature", matching("sha256=[a-f0-9]{64}"))
                .withHeader("X-Webhook-Timestamp", matching("[0-9]+"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"received\"}")));

        // Register endpoint
        CreateEndpointRequest epReq = new CreateEndpointRequest(
                "http://localhost:" + wireMockServer.port() + "/hook/success",
                List.of("user.signup")
        );
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(epReq, createHeaders(tenantId)), EndpointResponse.class);

        // Ingest event
        IngestEventRequest eventReq = new IngestEventRequest("usr_1", "user.signup", Map.of("email", "test@example.com"));
        ResponseEntity<EventResponse> ingestResp = restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(eventReq, createHeaders(tenantId)), EventResponse.class);
        UUID eventId = ingestResp.getBody().id();

        // Claim and execute delivery
        List<Delivery> tenantDeliveries = deliveryRepository.findByTenantId(tenantId);
        assertThat(tenantDeliveries).hasSize(1);
        UUID deliveryId = tenantDeliveries.get(0).getId();

        List<UUID> claimed = deliveryExecutionService.claimDueDeliveries(10, "test-worker", 60);
        assertThat(claimed).contains(deliveryId);

        deliveryExecutionService.executeDelivery(deliveryId);

        // Verify status and attempt
        Delivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getLastResponseCode()).isEqualTo(200);
        assertThat(delivery.getLastResponseSnippet()).contains("received");

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getResponseCode()).isEqualTo(200);
        assertThat(attempts.get(0).getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Delivery Lifecycle: 500 Failure retries, dead-letters at max attempts, and supports manual redrive")
    void testFailedDeliveryDeadLetterAndRedrive() {
        String tenantId = "tenant-fail-" + UUID.randomUUID();

        // Stub WireMock 500 endpoint
        wireMockServer.stubFor(post(urlEqualTo("/hook/error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // Register endpoint
        CreateEndpointRequest epReq = new CreateEndpointRequest(
                "http://localhost:" + wireMockServer.port() + "/hook/error",
                List.of("order.failed")
        );
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(epReq, createHeaders(tenantId)), EndpointResponse.class);

        // Ingest event
        IngestEventRequest eventReq = new IngestEventRequest("ord_fail_1", "order.failed", Map.of("reason", "insufficient_funds"));
        restTemplate.postForEntity("/api/v1/events", new HttpEntity<>(eventReq, createHeaders(tenantId)), EventResponse.class);

        // Claim and execute 1st failed attempt
        List<Delivery> tenantDeliveries = deliveryRepository.findByTenantId(tenantId);
        assertThat(tenantDeliveries).hasSize(1);
        UUID deliveryId = tenantDeliveries.get(0).getId();

        List<UUID> claimed = deliveryExecutionService.claimDueDeliveries(10, "test-worker", 60);
        assertThat(claimed).contains(deliveryId);

        deliveryExecutionService.executeDelivery(deliveryId);

        Delivery deliveryAfterAttempt1 = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(deliveryAfterAttempt1.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(deliveryAfterAttempt1.getAttemptCount()).isEqualTo(1);
        assertThat(deliveryAfterAttempt1.getNextAttemptAt()).isAfter(Instant.now());

        // Fast-forward delivery to max attempts (simulate 8 attempts)
        deliveryAfterAttempt1.setAttemptCount(7);
        deliveryAfterAttempt1.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(deliveryAfterAttempt1);

        List<UUID> claimed2 = deliveryExecutionService.claimDueDeliveries(10, "test-worker", 60);
        assertThat(claimed2).contains(deliveryId);

        deliveryExecutionService.executeDelivery(deliveryId);

        // Assert transitioned to DEAD_LETTERED
        Delivery deadLettered = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(deadLettered.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
        assertThat(deadLettered.getAttemptCount()).isEqualTo(8);

        // Test Manual Redrive Endpoint
        ResponseEntity<DeliveryResponse> redriveResp = restTemplate.postForEntity(
                "/api/v1/deliveries/" + deliveryId + "/redrive",
                new HttpEntity<>(createHeaders(tenantId)),
                DeliveryResponse.class
        );

        assertThat(redriveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeliveryResponse redriveBody = redriveResp.getBody();
        assertThat(redriveBody).isNotNull();
        assertThat(redriveBody.status()).isEqualTo(DeliveryStatus.PENDING);
    }
}
