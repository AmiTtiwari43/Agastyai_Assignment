package com.company.webhooks.observability;

import com.company.webhooks.delivery.DeliveryWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

@Component
public class DeliveryWorkerHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final Optional<DeliveryWorker> deliveryWorker;

    public DeliveryWorkerHealthIndicator(
            DataSource dataSource,
            @Autowired(required = false) Optional<DeliveryWorker> deliveryWorker) {
        this.dataSource = dataSource;
        this.deliveryWorker = deliveryWorker;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // 1. Check database connectivity
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            boolean valid = statement.execute("SELECT 1");
            builder.withDetail("database", valid ? "CONNECTED" : "FAILED");
        } catch (Exception e) {
            return Health.down()
                    .withDetail("database", "DOWN: " + e.getMessage())
                    .build();
        }

        // 2. Report worker pool health
        if (deliveryWorker.isPresent()) {
            DeliveryWorker worker = deliveryWorker.get();
            builder.withDetail("workerStatus", "RUNNING")
                    .withDetail("workerId", worker.getWorkerId())
                    .withDetail("activeDeliveriesCount", worker.getActiveDeliveriesCount());
        } else {
            builder.withDetail("workerStatus", "STANDBY_OR_DISABLED");
        }

        return builder.build();
    }
}
