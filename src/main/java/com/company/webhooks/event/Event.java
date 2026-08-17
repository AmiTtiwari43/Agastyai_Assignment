package com.company.webhooks.event;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "events",
        uniqueConstraints = @UniqueConstraint(name = "uk_events_tenant_external_id", columnNames = {"tenant_id", "event_id_external"})
)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "event_id_external", nullable = false, length = 255)
    private String eventIdExternal;

    @Column(nullable = false, length = 128)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Event() {}

    public Event(String tenantId, String eventIdExternal, String type, Map<String, Object> payload) {
        this.tenantId = tenantId;
        this.eventIdExternal = eventIdExternal;
        this.type = type;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getEventIdExternal() {
        return eventIdExternal;
    }

    public void setEventIdExternal(String eventIdExternal) {
        this.eventIdExternal = eventIdExternal;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
