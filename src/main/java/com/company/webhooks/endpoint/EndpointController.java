package com.company.webhooks.endpoint;

import com.company.webhooks.endpoint.dto.CreateEndpointRequest;
import com.company.webhooks.endpoint.dto.EndpointResponse;
import com.company.webhooks.endpoint.dto.EndpointTestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/endpoints")
@Tag(name = "Endpoints", description = "Webhook endpoint registration and lifecycle management")
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    @Operation(summary = "Register a new webhook endpoint", description = "Registers endpoint, generates HMAC signing secret, validates URL")
    public ResponseEntity<EndpointResponse> registerEndpoint(@Valid @RequestBody CreateEndpointRequest request) {
        EndpointResponse response = endpointService.registerEndpoint(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List all endpoints for current tenant")
    public ResponseEntity<List<EndpointResponse>> listEndpoints() {
        List<EndpointResponse> response = endpointService.listEndpoints();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get endpoint details by ID")
    public ResponseEntity<EndpointResponse> getEndpoint(@PathVariable UUID id) {
        EndpointResponse response = endpointService.getEndpoint(id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-disable an endpoint", description = "Disables future deliveries while preserving history")
    public ResponseEntity<EndpointResponse> disableEndpoint(@PathVariable UUID id) {
        EndpointResponse response = endpointService.disableEndpoint(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Self-test an endpoint", description = "Sends a synthetic signed test event to verify reachability and signature handling")
    public ResponseEntity<EndpointTestResponse> testEndpoint(@PathVariable UUID id) {
        EndpointTestResponse response = endpointService.testEndpoint(id);
        return ResponseEntity.ok(response);
    }
}
