package com.company.webhooks.endpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    List<Endpoint> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<Endpoint> findByIdAndTenantId(UUID id, String tenantId);

    @Query(value = "SELECT * FROM endpoints WHERE tenant_id = :tenantId AND status = 'ACTIVE' AND :eventType = ANY(subscribed_event_types)", nativeQuery = true)
    List<Endpoint> findActiveByTenantIdAndEventType(@Param("tenantId") String tenantId, @Param("eventType") String eventType);
}
