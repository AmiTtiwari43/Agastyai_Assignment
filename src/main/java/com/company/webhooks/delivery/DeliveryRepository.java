package com.company.webhooks.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByIdAndTenantId(UUID id, String tenantId);

    List<Delivery> findByTenantId(String tenantId);

    List<Delivery> findByEventId(UUID eventId);

    List<Delivery> findByEventIdAndTenantIdOrderByCreatedAtAsc(UUID eventId, String tenantId);

    int countByEventIdAndTenantId(UUID eventId, String tenantId);

    /**
     * DB-level Claiming Query using FOR UPDATE SKIP LOCKED (SPEC.md FR-3 & Guardrail requirement)
     */
    @Query(value = "SELECT id FROM deliveries " +
            "WHERE status = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY next_attempt_at ASC " +
            "LIMIT :batchSize " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> claimDueDeliveryIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * Atomically locks claimed deliveries for a worker
     */
    @Modifying
    @Query("UPDATE Delivery d SET d.status = 'IN_PROGRESS', d.lockedBy = :lockedBy, d.lockedUntil = :lockedUntil, d.updatedAt = :now " +
            "WHERE d.id IN :ids AND d.status = 'PENDING'")
    int markDeliveriesInProgress(@Param("ids") List<UUID> ids,
                                @Param("lockedBy") String lockedBy,
                                @Param("lockedUntil") Instant lockedUntil,
                                @Param("now") Instant now);

    /**
     * Lease timeout sweeper: Reclaims crashed/abandoned worker leases (FR-7 & Resilience requirement)
     */
    @Modifying
    @Query("UPDATE Delivery d SET d.status = 'PENDING', d.lockedBy = null, d.lockedUntil = null, d.updatedAt = :now " +
            "WHERE d.status = 'IN_PROGRESS' AND d.lockedUntil < :now")
    int recoverAbandonedLeases(@Param("now") Instant now);

    /**
     * Paginated and filtered delivery query scoped to tenant and endpoint (FR-4)
     */
    @Query("SELECT d FROM Delivery d " +
            "WHERE d.tenantId = :tenantId AND d.endpoint.id = :endpointId " +
            "AND (:status IS NULL OR d.status = :status) " +
            "AND (:fromDate IS NULL OR d.createdAt >= :fromDate) " +
            "AND (:toDate IS NULL OR d.createdAt <= :toDate) " +
            "ORDER BY d.createdAt DESC")
    Page<Delivery> findEndpointDeliveriesFiltered(
            @Param("tenantId") String tenantId,
            @Param("endpointId") UUID endpointId,
            @Param("status") DeliveryStatus status,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            Pageable pageable);
}
