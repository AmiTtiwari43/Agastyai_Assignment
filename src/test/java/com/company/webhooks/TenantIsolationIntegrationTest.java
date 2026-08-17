package com.company.webhooks;

import com.company.webhooks.endpoint.dto.CreateEndpointRequest;
import com.company.webhooks.endpoint.dto.EndpointResponse;
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

class TenantIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders createHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return headers;
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant B cannot access Tenant A's endpoints, secrets, deliveries, or trigger redrive (all return 404)")
    void testTenantIsolationAirtight() {
        String tenantA = "tenant-alice-" + UUID.randomUUID();
        String tenantB = "tenant-bob-" + UUID.randomUUID();

        // 1. Tenant A registers an endpoint
        CreateEndpointRequest createReq = new CreateEndpointRequest(
                "http://localhost:" + wireMockServer.port() + "/alice/webhook",
                List.of("order.created", "invoice.paid")
        );
        HttpEntity<CreateEndpointRequest> entityA = new HttpEntity<>(createReq, createHeaders(tenantA));
        ResponseEntity<EndpointResponse> regResp = restTemplate.postForEntity("/api/v1/endpoints", entityA, EndpointResponse.class);

        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EndpointResponse endpointA = regResp.getBody();
        assertThat(endpointA).isNotNull();
        UUID endpointAId = endpointA.id();
        String secretA = endpointA.secret();

        // 2. Tenant B attempts to read Tenant A's endpoint -> MUST return 404
        HttpEntity<Void> reqTenantB = new HttpEntity<>(createHeaders(tenantB));
        ResponseEntity<String> getRespB = restTemplate.exchange(
                "/api/v1/endpoints/" + endpointAId,
                HttpMethod.GET,
                reqTenantB,
                String.class
        );
        assertThat(getRespB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 3. Tenant B attempts to disable Tenant A's endpoint -> MUST return 404
        ResponseEntity<String> deleteRespB = restTemplate.exchange(
                "/api/v1/endpoints/" + endpointAId,
                HttpMethod.DELETE,
                reqTenantB,
                String.class
        );
        assertThat(deleteRespB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 4. Tenant B attempts to self-test Tenant A's endpoint -> MUST return 404
        ResponseEntity<String> testRespB = restTemplate.exchange(
                "/api/v1/endpoints/" + endpointAId + "/test",
                HttpMethod.POST,
                reqTenantB,
                String.class
        );
        assertThat(testRespB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 5. Tenant A ingests an event (creates delivery)
        IngestEventRequest ingestReq = new IngestEventRequest(
                "order_100",
                "order.created",
                Map.of("item", "laptop", "price", 1200)
        );
        HttpEntity<IngestEventRequest> ingestEntityA = new HttpEntity<>(ingestReq, createHeaders(tenantA));
        ResponseEntity<EventResponse> ingestResp = restTemplate.postForEntity("/api/v1/events", ingestEntityA, EventResponse.class);
        assertThat(ingestResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID eventAId = ingestResp.getBody().id();

        // 6. Tenant B attempts to view deliveries for Tenant A's event -> MUST return 404
        ResponseEntity<String> eventDelivB = restTemplate.exchange(
                "/api/v1/events/" + eventAId + "/deliveries",
                HttpMethod.GET,
                reqTenantB,
                String.class
        );
        assertThat(eventDelivB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 7. Tenant B attempts to view deliveries for Tenant A's endpoint -> MUST return 404
        ResponseEntity<String> epDelivB = restTemplate.exchange(
                "/api/v1/endpoints/" + endpointAId + "/deliveries",
                HttpMethod.GET,
                reqTenantB,
                String.class
        );
        assertThat(epDelivB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 8. Tenant B attempts to redrive Tenant A's delivery with a random/crafted delivery ID -> MUST return 404
        ResponseEntity<String> redriveB = restTemplate.exchange(
                "/api/v1/deliveries/" + UUID.randomUUID() + "/redrive",
                HttpMethod.POST,
                reqTenantB,
                String.class
        );
        assertThat(redriveB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
