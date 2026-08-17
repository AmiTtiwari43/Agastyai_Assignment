package com.company.webhooks.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EndpointCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public static class EndpointState {
        private int consecutiveFailures = 0;
        private State state = State.CLOSED;
        private Instant openUntil = Instant.EPOCH;

        public synchronized boolean allowExecution(long cooldownSeconds) {
            Instant now = Instant.now();
            if (state == State.OPEN) {
                if (now.isAfter(openUntil)) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            }
            return true;
        }

        public synchronized void recordSuccess() {
            consecutiveFailures = 0;
            state = State.CLOSED;
            openUntil = Instant.EPOCH;
        }

        public synchronized void recordFailure(int threshold, long cooldownSeconds) {
            consecutiveFailures++;
            if (state == State.HALF_OPEN || consecutiveFailures >= threshold) {
                state = State.OPEN;
                openUntil = Instant.now().plus(Duration.ofSeconds(cooldownSeconds));
            }
        }

        public synchronized State getState() {
            if (state == State.OPEN && Instant.now().isAfter(openUntil)) {
                return State.HALF_OPEN;
            }
            return state;
        }

        public synchronized Duration getRemainingCooldown() {
            if (state != State.OPEN) {
                return Duration.ZERO;
            }
            Duration remaining = Duration.between(Instant.now(), openUntil);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
    }

    private final int failureThreshold;
    private final long cooldownSeconds;
    private final Map<UUID, EndpointState> endpointStates = new ConcurrentHashMap<>();

    public EndpointCircuitBreaker(
            @Value("${webhooks.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${webhooks.circuit-breaker.cooldown-seconds:60}") long cooldownSeconds) {
        this.failureThreshold = failureThreshold;
        this.cooldownSeconds = cooldownSeconds;
    }

    public boolean allowDelivery(UUID endpointId) {
        EndpointState state = endpointStates.computeIfAbsent(endpointId, k -> new EndpointState());
        return state.allowExecution(cooldownSeconds);
    }

    public void recordSuccess(UUID endpointId) {
        EndpointState state = endpointStates.get(endpointId);
        if (state != null) {
            state.recordSuccess();
        }
    }

    public void recordFailure(UUID endpointId) {
        EndpointState state = endpointStates.computeIfAbsent(endpointId, k -> new EndpointState());
        state.recordFailure(failureThreshold, cooldownSeconds);
    }

    public State getState(UUID endpointId) {
        EndpointState state = endpointStates.get(endpointId);
        return state != null ? state.getState() : State.CLOSED;
    }

    public Duration getRemainingCooldown(UUID endpointId) {
        EndpointState state = endpointStates.get(endpointId);
        return state != null ? state.getRemainingCooldown() : Duration.ZERO;
    }

    public void reset(UUID endpointId) {
        endpointStates.remove(endpointId);
    }
}
