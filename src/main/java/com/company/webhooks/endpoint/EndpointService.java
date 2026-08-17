package com.company.webhooks.endpoint;

import com.company.webhooks.common.exception.ResourceNotFoundException;
import com.company.webhooks.delivery.WebhookDispatcher;
import com.company.webhooks.endpoint.dto.CreateEndpointRequest;
import com.company.webhooks.endpoint.dto.EndpointResponse;
import com.company.webhooks.endpoint.dto.EndpointTestResponse;
import com.company.webhooks.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final UrlValidator urlValidator;
    private final SecretGenerator secretGenerator;
    private final WebhookDispatcher webhookDispatcher;

    public EndpointService(EndpointRepository endpointRepository,
                           UrlValidator urlValidator,
                           SecretGenerator secretGenerator,
                           WebhookDispatcher webhookDispatcher) {
        this.endpointRepository = endpointRepository;
        this.urlValidator = urlValidator;
        this.secretGenerator = secretGenerator;
        this.webhookDispatcher = webhookDispatcher;
    }

    @Transactional
    public EndpointResponse registerEndpoint(CreateEndpointRequest request) {
        String tenantId = TenantContext.requireTenantId();
        urlValidator.validateUrl(request.url());

        String secret = secretGenerator.generateSecret();
        Endpoint endpoint = new Endpoint(tenantId, request.url().trim(), secret, request.subscribedEventTypes());
        Endpoint saved = endpointRepository.save(endpoint);
        return EndpointResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> listEndpoints() {
        String tenantId = TenantContext.requireTenantId();
        return endpointRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(EndpointResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public EndpointResponse getEndpoint(UUID id) {
        String tenantId = TenantContext.requireTenantId();
        Endpoint endpoint = endpointRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint not found with id: " + id));
        return EndpointResponse.fromEntity(endpoint);
    }

    @Transactional
    public EndpointResponse disableEndpoint(UUID id) {
        String tenantId = TenantContext.requireTenantId();
        Endpoint endpoint = endpointRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint not found with id: " + id));

        endpoint.setStatus(EndpointStatus.DISABLED);
        Endpoint saved = endpointRepository.save(endpoint);
        return EndpointResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public EndpointTestResponse testEndpoint(UUID id) {
        String tenantId = TenantContext.requireTenantId();
        Endpoint endpoint = endpointRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint not found with id: " + id));

        Map<String, Object> testPayload = Map.of(
                "event", "endpoint.test",
                "timestamp", Instant.now().toString(),
                "message", "Synthetic test event for signature verification and connectivity check"
        );

        WebhookDispatcher.DispatchResult result = webhookDispatcher.dispatch(
                endpoint.getUrl(),
                endpoint.getSecret(),
                testPayload,
                "test-" + UUID.randomUUID()
        );

        return new EndpointTestResponse(
                result.success(),
                result.statusCode(),
                result.latencyMs(),
                result.success() ? "Endpoint reached successfully" : result.errorMessage(),
                result.responseSnippet()
        );
    }
}
