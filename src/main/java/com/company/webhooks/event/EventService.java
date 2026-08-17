package com.company.webhooks.event;

import com.company.webhooks.delivery.Delivery;
import com.company.webhooks.delivery.DeliveryRepository;
import com.company.webhooks.endpoint.Endpoint;
import com.company.webhooks.endpoint.EndpointRepository;
import com.company.webhooks.event.dto.EventResponse;
import com.company.webhooks.event.dto.IngestEventRequest;
import com.company.webhooks.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryRepository deliveryRepository;

    public EventService(EventRepository eventRepository,
                        EndpointRepository endpointRepository,
                        DeliveryRepository deliveryRepository) {
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
    }

    @Transactional
    public EventResponse ingestEvent(IngestEventRequest request) {
        String tenantId = TenantContext.requireTenantId();

        // 1. Check idempotency: Return existing event if previously submitted
        Optional<Event> existingEventOpt = eventRepository.findByTenantIdAndEventIdExternal(tenantId, request.eventId());
        if (existingEventOpt.isPresent()) {
            Event existing = existingEventOpt.get();
            int existingDeliveries = deliveryRepository.countByEventIdAndTenantId(existing.getId(), tenantId);
            log.info("Idempotent duplicate event received [tenantId={}, eventIdExternal={}]", tenantId, request.eventId());
            return EventResponse.fromEntity(existing, "ACCEPTED", existingDeliveries);
        }

        // 2. Persist new event
        Event event = new Event(tenantId, request.eventId().trim(), request.type().trim(), request.payload());
        try {
            event = eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            // Concurrent race on (tenant_id, event_id_external) handled gracefully
            return eventRepository.findByTenantIdAndEventIdExternal(tenantId, request.eventId())
                    .map(saved -> EventResponse.fromEntity(saved, "ACCEPTED", deliveryRepository.countByEventIdAndTenantId(saved.getId(), tenantId)))
                    .orElseThrow(() -> e);
        }

        // 3. Fan-out to subscribed active endpoints
        List<Endpoint> matchingEndpoints = endpointRepository.findActiveByTenantIdAndEventType(tenantId, event.getType());
        if (!matchingEndpoints.isEmpty()) {
            final Event finalEvent = event;
            List<Delivery> pendingDeliveries = matchingEndpoints.stream()
                    .map(endpoint -> new Delivery(finalEvent, endpoint, tenantId))
                    .toList();
            deliveryRepository.saveAll(pendingDeliveries);
            log.info("Fanned out event [tenantId={}, eventId={}, deliveriesCount={}]", tenantId, event.getId(), pendingDeliveries.size());
        }

        return EventResponse.fromEntity(event, "ACCEPTED", matchingEndpoints.size());
    }
}
