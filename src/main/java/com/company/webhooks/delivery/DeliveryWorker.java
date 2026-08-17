package com.company.webhooks.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "webhooks.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryExecutionService executionService;
    private final String workerId;
    private final int batchSize;
    private final int leaseDurationSeconds;
    private final ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeDeliveriesCount = new AtomicInteger(0);

    public DeliveryWorker(
            DeliveryExecutionService executionService,
            @Value("${webhooks.worker.instance-id:worker-default}") String workerId,
            @Value("${webhooks.worker.batch-size:50}") int batchSize,
            @Value("${webhooks.worker.lease-duration-seconds:120}") int leaseDurationSeconds) {
        this.executionService = executionService;
        this.workerId = workerId;
        this.batchSize = batchSize;
        this.leaseDurationSeconds = leaseDurationSeconds;
        // Java 21 Virtual Threads executor for lightweight concurrent delivery execution
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Scheduled(fixedDelayString = "${webhooks.worker.poll-interval-ms:1000}")
    public void pollAndDeliver() {
        if (!isRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            List<UUID> claimedIds = executionService.claimDueDeliveries(batchSize, workerId, leaseDurationSeconds);
            if (!claimedIds.isEmpty()) {
                log.debug("Claimed {} due deliveries for worker {}", claimedIds.size(), workerId);
                for (UUID id : claimedIds) {
                    activeDeliveriesCount.incrementAndGet();
                    executorService.submit(() -> {
                        try {
                            executionService.executeDelivery(id);
                        } catch (Exception e) {
                            log.error("Unexpected error executing delivery [deliveryId={}]: {}", id, e.getMessage(), e);
                        } finally {
                            activeDeliveriesCount.decrementAndGet();
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Error during delivery claim cycle: {}", e.getMessage(), e);
        } finally {
            isRunning.set(false);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void recoverAbandonedLeases() {
        try {
            executionService.recoverAbandonedLeases();
        } catch (Exception e) {
            log.error("Error during lease recovery cycle: {}", e.getMessage(), e);
        }
    }

    public int getActiveDeliveriesCount() {
        return activeDeliveriesCount.get();
    }

    public String getWorkerId() {
        return workerId;
    }
}
