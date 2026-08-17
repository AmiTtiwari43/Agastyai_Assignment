package com.company.webhooks;

import com.company.webhooks.delivery.DeliveryRepository;
import com.company.webhooks.endpoint.dto.CreateEndpointRequest;
import com.company.webhooks.event.dto.EventResponse;
import com.company.webhooks.event.dto.IngestEventRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventIdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    private HttpHeaders createHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Idempotency: Re-submitting identical eventId for same tenant returns 202 and creates zero extra deliveries")
    void testEventIdempotency() {
        String tenantId = "tenant-idemp-" + UUID.randomUUID();
        String externalEventId = "evt_payment_999";

        // Register 2 endpoints subscribed to 'payment.success'
        CreateEndpointRequest ep1 = new CreateEndpointRequest(
                "http://localhost:" + wireMockServer.port() + "/idemp/ep1",
                List.of("payment.success")
        );
        CreateEndpointRequest ep2 = new CreateEndpointRequest(
                "http://localhost:" + wireMockServer.port() + "/idemp/ep2",
                List.of("payment.success")
        );
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(ep1, createHeaders(tenantId)), String.class);
        restTemplate.postForEntity("/api/v1/endpoints", new HttpEntity<>(ep2, createHeaders(tenantId)), String.class);

        // 1. First event submission
        IngestEventRequest request1 = new IngestEventRequest(
                externalEventId,
                "payment.success",
                Map.of("amount", 250, "currency", "USD")
        );
        ResponseEntity<EventResponse> resp1 = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(request1, createHeaders(tenantId)),
                EventResponse.class
        );

        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        EventResponse event1 = resp1.getBody();
        assertThat(event1).isNotNull();
        assertThat(event1.deliveriesCount()).isEqualTo(2);

        int initialDeliveryCount = deliveryRepository.countByEventIdAndTenantId(event1.id(), tenantId);
        assertThat(initialDeliveryCount).isEqualTo(2);

        // 2. Duplicate submission with same (tenantId, eventId)
        IngestEventRequest duplicateRequest = new IngestEventRequest(
                externalEventId,
                "payment.success",
                Map.of("amount", 250, "currency", "USD")
        );
        ResponseEntity<EventResponse> resp2 = restTemplate.postForEntity(
                "/api/v1/events",
                new HttpEntity<>(duplicateRequest, createHeaders(tenantId)),
                EventResponse.class
        );

        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        EventResponse event2 = resp2.getBody();
        assertThat(event2).isNotNull();
        assertThat(event2.id()).isEqualTo(event1.id()); // Same internal ID returned

        // Verify total deliveries in DB did NOT increase
        int afterDuplicateDeliveryCount = deliveryRepository.countByEventIdAndTenantId(event1.id(), tenantId);
        assertThat(afterDuplicateDeliveryCount).isEqualTo(2);
    }
}
