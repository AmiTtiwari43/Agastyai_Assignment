package com.company.webhooks.delivery;

import com.company.webhooks.deliveryattempt.DeliveryAttempt;
import com.company.webhooks.deliveryattempt.DeliveryAttemptRepository;
import com.company.webhooks.endpoint.Endpoint;
import com.company.webhooks.endpoint.EndpointStatus;
import com.company.webhooks.event.Event;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DeliveryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryExecutionService.class);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final WebhookDispatcher webhookDispatcher;
    private final BackoffCalculator backoffCalculator;
    private final EndpointCircuitBreaker circuitBreaker;
    private final int maxAttempts;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter deadLetterCounter;
    private final Timer deliveryLatencyTimer;

    public DeliveryExecutionService(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            WebhookDispatcher webhookDispatcher,
            BackoffCalculator backoffCalculator,
            EndpointCircuitBreaker circuitBreaker,
            MeterRegistry meterRegistry,
            @Value("${webhooks.retry.max-attempts:8}") int maxAttempts) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.webhookDispatcher = webhookDispatcher;
        this.backoffCalculator = backoffCalculator;
        this.circuitBreaker = circuitBreaker;
        this.maxAttempts = maxAttempts;

        this.successCounter = meterRegistry.counter("webhook.deliveries.total", "outcome", "SUCCESS");
        this.failureCounter = meterRegistry.counter("webhook.deliveries.total", "outcome", "FAILURE");
        this.deadLetterCounter = meterRegistry.counter("webhook.deliveries.total", "outcome", "DEAD_LETTERED");
        this.deliveryLatencyTimer = meterRegistry.timer("webhook.deliveries.latency");
    }

    /**
     * Atomically claims a batch of due deliveries at the DB level using FOR UPDATE SKIP LOCKED
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimDueDeliveries(int batchSize, String workerId, int leaseSeconds) {
        Instant now = Instant.now();
        List<UUID> claimedIds = deliveryRepository.claimDueDeliveryIds(now, batchSize);
        if (!claimedIds.isEmpty()) {
            Instant leaseUntil = now.plus(Duration.ofSeconds(leaseSeconds));
            deliveryRepository.markDeliveriesInProgress(claimedIds, workerId, leaseUntil, now);
        }
        return claimedIds;
    }

    /**
     * Executes the HTTP delivery and updates status in an isolated transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeDelivery(UUID deliveryId) {
        Optional<Delivery> deliveryOpt = deliveryRepository.findById(deliveryId);
        if (deliveryOpt.isEmpty()) {
            return;
        }

        Delivery delivery = deliveryOpt.get();
        Endpoint endpoint = delivery.getEndpoint();
        Event event = delivery.getEvent();
        String tenantId = delivery.getTenantId();

        // Establish correlation ID for delivery logging
        String correlationId = "deliv-" + delivery.getId();
        MDC.put("correlationId", correlationId);
        MDC.put("tenantId", tenantId);

        try {
            // Check if endpoint is disabled
            if (endpoint.getStatus() == EndpointStatus.DISABLED) {
                log.info("Endpoint is disabled. Cancelling delivery [deliveryId={}, endpointId={}]", deliveryId, endpoint.getId());
                delivery.setStatus(DeliveryStatus.DEAD_LETTERED);
                delivery.setLastResponseSnippet("Endpoint is disabled");
                delivery.setLockedBy(null);
                delivery.setLockedUntil(null);
                deliveryRepository.save(delivery);
                return;
            }

            // Check Circuit Breaker for this endpoint
            if (!circuitBreaker.allowDelivery(endpoint.getId())) {
                Duration cooldown = circuitBreaker.getRemainingCooldown(endpoint.getId());
                log.warn("Circuit breaker OPEN for endpoint [endpointId={}]. Delaying delivery by {}s", endpoint.getId(), cooldown.toSeconds());
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery.setNextAttemptAt(Instant.now().plus(cooldown.isZero() ? Duration.ofSeconds(10) : cooldown));
                delivery.setLockedBy(null);
                delivery.setLockedUntil(null);
                deliveryRepository.save(delivery);
                return;
            }

            int currentAttempt = delivery.getAttemptCount() + 1;

            // Dispatch outbound HTTP request
            WebhookDispatcher.DispatchResult result = webhookDispatcher.dispatch(
                    endpoint.getUrl(),
                    endpoint.getSecret(),
                    event.getPayload(),
                    correlationId
            );

            deliveryLatencyTimer.record(result.latencyMs(), TimeUnit.MILLISECONDS);

            // Record delivery attempt history
            DeliveryAttempt attempt = new DeliveryAttempt(
                    delivery.getId(),
                    currentAttempt,
                    result.statusCode(),
                    result.latencyMs(),
                    result.errorMessage()
            );
            deliveryAttemptRepository.save(attempt);

            if (result.success()) {
                // SUCCESS
                successCounter.increment();
                circuitBreaker.recordSuccess(endpoint.getId());

                delivery.setStatus(DeliveryStatus.DELIVERED);
                delivery.setAttemptCount(currentAttempt);
                delivery.setLastResponseCode(result.statusCode());
                delivery.setLastResponseSnippet(result.responseSnippet());
                delivery.setLockedBy(null);
                delivery.setLockedUntil(null);
                log.info("Delivery successful [deliveryId={}, endpointId={}, attempt={}, latency={}ms]",
                        deliveryId, endpoint.getId(), currentAttempt, result.latencyMs());
            } else {
                // FAILURE
                failureCounter.increment();
                circuitBreaker.recordFailure(endpoint.getId());

                delivery.setAttemptCount(currentAttempt);
                delivery.setLastResponseCode(result.statusCode());
                delivery.setLastResponseSnippet(result.errorMessage() != null ? result.errorMessage() : result.responseSnippet());

                if (currentAttempt >= maxAttempts) {
                    // DEAD LETTER
                    deadLetterCounter.increment();
                    delivery.setStatus(DeliveryStatus.DEAD_LETTERED);
                    delivery.setLockedBy(null);
                    delivery.setLockedUntil(null);
                    log.warn("Delivery dead-lettered after {} attempts [deliveryId={}, endpointId={}]",
                            currentAttempt, deliveryId, endpoint.getId());
                } else {
                    // RETRY WITH BACKOFF + JITTER
                    Duration delay = backoffCalculator.calculateDelayWithJitter(currentAttempt);
                    delivery.setStatus(DeliveryStatus.PENDING);
                    delivery.setNextAttemptAt(Instant.now().plus(delay));
                    delivery.setLockedBy(null);
                    delivery.setLockedUntil(null);
                    log.warn("Delivery failed, scheduling retry {}/{} in {}s [deliveryId={}, endpointId={}, error={}]",
                            currentAttempt, maxAttempts, delay.toSeconds(), deliveryId, endpoint.getId(), result.errorMessage());
                }
            }

            deliveryRepository.save(delivery);

        } finally {
            MDC.remove("correlationId");
            MDC.remove("tenantId");
        }
    }

    /**
     * Recovers abandoned leases where worker crashed mid-lease
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverAbandonedLeases() {
        int recovered = deliveryRepository.recoverAbandonedLeases(Instant.now());
        if (recovered > 0) {
            log.warn("Recovered {} abandoned delivery leases", recovered);
        }
        return recovered;
    }
}
