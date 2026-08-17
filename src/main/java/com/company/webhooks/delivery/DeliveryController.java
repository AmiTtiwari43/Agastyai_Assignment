package com.company.webhooks.delivery;

import com.company.webhooks.delivery.dto.DeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Deliveries", description = "Delivery visibility, filtering, and manual redrive operations")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping("/api/v1/events/{id}/deliveries")
    @Operation(summary = "Get all delivery attempts for an event")
    public ResponseEntity<List<DeliveryResponse>> getDeliveriesForEvent(@PathVariable("id") UUID eventId) {
        List<DeliveryResponse> response = deliveryService.getDeliveriesForEvent(eventId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/endpoints/{id}/deliveries")
    @Operation(summary = "Get paginated, filtered delivery history for an endpoint")
    public ResponseEntity<Page<DeliveryResponse>> getDeliveriesForEndpoint(
            @PathVariable("id") UUID endpointId,
            @RequestParam(name = "status", required = false) DeliveryStatus status,
            @RequestParam(name = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,
            @RequestParam(name = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DeliveryResponse> response = deliveryService.getDeliveriesForEndpoint(
                endpointId, status, fromDate, toDate, pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/deliveries/{id}/redrive")
    @Operation(summary = "Manually re-queue a dead-lettered or failed delivery")
    public ResponseEntity<DeliveryResponse> redriveDelivery(@PathVariable("id") UUID deliveryId) {
        DeliveryResponse response = deliveryService.redriveDelivery(deliveryId);
        return ResponseEntity.ok(response);
    }
}
