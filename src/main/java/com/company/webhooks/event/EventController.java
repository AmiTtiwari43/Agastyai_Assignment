package com.company.webhooks.event;

import com.company.webhooks.event.dto.EventResponse;
import com.company.webhooks.event.dto.IngestEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Webhook event ingestion with idempotency and fan-out")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(summary = "Ingest a new event", description = "Accepts event, deduplicates on (tenantId, eventId), fans out to matching endpoints, returns 202 Accepted")
    public ResponseEntity<EventResponse> ingestEvent(@Valid @RequestBody IngestEventRequest request) {
        EventResponse response = eventService.ingestEvent(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
