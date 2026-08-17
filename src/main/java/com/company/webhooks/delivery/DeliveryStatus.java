package com.company.webhooks.delivery;

public enum DeliveryStatus {
    PENDING,
    IN_PROGRESS,
    DELIVERED,
    FAILED_RETRYING,
    DEAD_LETTERED
}
