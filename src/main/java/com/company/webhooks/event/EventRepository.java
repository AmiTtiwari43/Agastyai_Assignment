package com.company.webhooks.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByIdAndTenantId(UUID id, String tenantId);

    Optional<Event> findByTenantIdAndEventIdExternal(String tenantId, String eventIdExternal);
}
