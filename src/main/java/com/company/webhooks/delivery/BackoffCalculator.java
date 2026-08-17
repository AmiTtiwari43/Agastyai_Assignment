package com.company.webhooks.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class BackoffCalculator {

    private final long baseDelaySeconds;
    private final long maxDelaySeconds;
    private final double jitterPercent;

    public BackoffCalculator(
            @Value("${webhooks.retry.base-delay-seconds:30}") long baseDelaySeconds,
            @Value("${webhooks.retry.max-delay-seconds:21600}") long maxDelaySeconds,
            @Value("${webhooks.retry.jitter-percent:0.15}") double jitterPercent) {
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.jitterPercent = jitterPercent;
    }

    /**
     * Calculates deterministic exponential backoff without jitter
     */
    public Duration calculateDeterministicDelay(int attempt) {
        if (attempt <= 0) {
            attempt = 1;
        }

        // attempt 1 -> 2^0 = 1, attempt 2 -> 2^1 = 2, etc.
        // Cap exponent at 30 to avoid integer overflow
        int exponent = Math.min(attempt - 1, 30);
        long multiplier = 1L << exponent;

        long rawDelay;
        if (multiplier > maxDelaySeconds / Math.max(1, baseDelaySeconds)) {
            rawDelay = maxDelaySeconds;
        } else {
            rawDelay = Math.min(baseDelaySeconds * multiplier, maxDelaySeconds);
        }

        return Duration.ofSeconds(rawDelay);
    }

    /**
     * Calculates exponential backoff with configured uniform random jitter (+/- jitterPercent)
     */
    public Duration calculateDelayWithJitter(int attempt) {
        Duration deterministic = calculateDeterministicDelay(attempt);
        long baseSeconds = deterministic.toSeconds();

        if (jitterPercent <= 0.0 || baseSeconds == 0) {
            return deterministic;
        }

        double maxJitter = baseSeconds * jitterPercent;
        // Random value between -maxJitter and +maxJitter
        double jitter = ThreadLocalRandom.current().nextDouble(-maxJitter, maxJitter);
        long finalSeconds = Math.max(1, Math.round(baseSeconds + jitter));

        return Duration.ofSeconds(finalSeconds);
    }

    public long getBaseDelaySeconds() {
        return baseDelaySeconds;
    }

    public long getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public double getJitterPercent() {
        return jitterPercent;
    }
}
