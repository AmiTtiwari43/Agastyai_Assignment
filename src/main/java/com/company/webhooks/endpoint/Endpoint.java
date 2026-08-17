package com.company.webhooks.endpoint;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "endpoints")
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 128)
    private String secret;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "subscribed_event_types", columnDefinition = "text[]", nullable = false)
    private List<String> subscribedEventTypes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EndpointStatus status = EndpointStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Endpoint() {}

    public Endpoint(String tenantId, String url, String secret, List<String> subscribedEventTypes) {
        this.tenantId = tenantId;
        this.url = url;
        this.secret = secret;
        this.subscribedEventTypes = subscribedEventTypes;
        this.status = EndpointStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public List<String> getSubscribedEventTypes() {
        return subscribedEventTypes;
    }

    public void setSubscribedEventTypes(List<String> subscribedEventTypes) {
        this.subscribedEventTypes = subscribedEventTypes;
    }

    public EndpointStatus getStatus() {
        return status;
    }

    public void setStatus(EndpointStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
