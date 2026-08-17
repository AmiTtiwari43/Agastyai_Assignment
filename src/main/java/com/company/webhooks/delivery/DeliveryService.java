package com.company.webhooks.delivery;

import com.company.webhooks.common.exception.ResourceNotFoundException;
import com.company.webhooks.delivery.dto.DeliveryAttemptResponse;
import com.company.webhooks.delivery.dto.DeliveryResponse;
import com.company.webhooks.deliveryattempt.DeliveryAttemptRepository;
import com.company.webhooks.endpoint.EndpointRepository;
import com.company.webhooks.event.EventRepository;
import com.company.webhooks.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryAttemptRepository deliveryAttemptRepository,
                           EventRepository eventRepository,
                           EndpointRepository endpointRepository) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> getDeliveriesForEvent(UUID eventId) {
        String tenantId = TenantContext.requireTenantId();
        // Ensure event exists and belongs to tenant
        eventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));

        List<Delivery> deliveries = deliveryRepository.findByEventIdAndTenantIdOrderByCreatedAtAsc(eventId, tenantId);
        return deliveries.stream().map(delivery -> {
            List<DeliveryAttemptResponse> attempts = deliveryAttemptRepository
                    .findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId())
                    .stream()
                    .map(DeliveryAttemptResponse::fromEntity)
                    .toList();
            return DeliveryResponse.fromEntity(delivery, attempts);
        }).toList();
    }

    @Transactional(readOnly = true)
    public Page<DeliveryResponse> getDeliveriesForEndpoint(
            UUID endpointId,
            DeliveryStatus status,
            Instant fromDate,
            Instant toDate,
            Pageable pageable) {
        String tenantId = TenantContext.requireTenantId();
        // Ensure endpoint exists and belongs to tenant (returns 404 for cross-tenant)
        endpointRepository.findByIdAndTenantId(endpointId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint not found with id: " + endpointId));

        Page<Delivery> deliveryPage = deliveryRepository.findEndpointDeliveriesFiltered(
                tenantId, endpointId, status, fromDate, toDate, pageable);

        return deliveryPage.map(DeliveryResponse::fromEntity);
    }

    @Transactional
    public DeliveryResponse redriveDelivery(UUID deliveryId) {
        String tenantId = TenantContext.requireTenantId();
        Delivery delivery = deliveryRepository.findByIdAndTenantId(deliveryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found with id: " + deliveryId));

        // Re-queue dead-lettered or failed delivery
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setNextAttemptAt(Instant.now());
        delivery.setLockedBy(null);
        delivery.setLockedUntil(null);

        Delivery saved = deliveryRepository.save(delivery);
        List<DeliveryAttemptResponse> attempts = deliveryAttemptRepository
                .findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId())
                .stream()
                .map(DeliveryAttemptResponse::fromEntity)
                .toList();

        return DeliveryResponse.fromEntity(saved, attempts);
    }
}
