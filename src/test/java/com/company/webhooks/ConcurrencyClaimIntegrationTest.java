package com.company.webhooks;

import com.company.webhooks.delivery.Delivery;
import com.company.webhooks.delivery.DeliveryExecutionService;
import com.company.webhooks.delivery.DeliveryRepository;
import com.company.webhooks.endpoint.Endpoint;
import com.company.webhooks.endpoint.EndpointRepository;
import com.company.webhooks.event.Event;
import com.company.webhooks.event.EventRepository;
import com.company.webhooks.tenant.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyClaimIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DeliveryExecutionService deliveryExecutionService;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private TenantService tenantService;

    @Test
    @DisplayName("Concurrency: Parallel workers racing FOR UPDATE SKIP LOCKED claim queries never double-claim the same delivery")
    void testConcurrentWorkersNeverDoubleClaim() throws InterruptedException, ExecutionException {
        String tenantId = "tenant-concurrent-" + UUID.randomUUID();
        tenantService.ensureTenantExists(tenantId);

        // 1. Create Endpoint and Event
        Endpoint endpoint = endpointRepository.save(new Endpoint(
                tenantId,
                "http://localhost:" + wireMockServer.port() + "/concurrent/hook",
                "secret_key_123",
                List.of("stock.updated")
        ));

        Event event = eventRepository.save(new Event(
                tenantId,
                "stock_evt_1",
                "stock.updated",
                Map.of("sku", "ITEM-101", "qty", 50)
        ));

        // 2. Create 20 pending deliveries
        List<UUID> createdDeliveryIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Delivery delivery = deliveryRepository.save(new Delivery(event, endpoint, tenantId));
            createdDeliveryIds.add(delivery.getId());
        }

        // 3. Launch 10 concurrent threads to race claiming the deliveries
        int numThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<List<UUID>>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final String workerName = "worker-thread-" + i;
            futures.add(executorService.submit(() -> {
                startLatch.await(); // Wait for all threads to be ready to maximize contention
                return deliveryExecutionService.claimDueDeliveries(10, workerName, 60);
            }));
        }

        // Release the latch to start simultaneous execution
        startLatch.countDown();

        // 4. Collect all claimed IDs across all worker threads
        Set<UUID> allClaimedIds = new HashSet<>();
        int totalClaimsCount = 0;

        for (Future<List<UUID>> future : futures) {
            List<UUID> workerClaimed = future.get();
            for (UUID id : workerClaimed) {
                totalClaimsCount++;
                boolean isUnique = allClaimedIds.add(id);
                // If isUnique is false, a delivery was claimed more than once!
                assertThat(isUnique)
                        .withFailMessage("Double-claim detected on delivery ID: " + id)
                        .isTrue();
            }
        }

        // Total claimed IDs must exactly equal the unique set size (Zero duplicates)
        assertThat(totalClaimsCount).isEqualTo(allClaimedIds.size());
        assertThat(allClaimedIds.size()).isEqualTo(20);

        executorService.shutdown();
    }
}
