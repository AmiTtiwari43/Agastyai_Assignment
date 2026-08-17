package com.company.webhooks.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointCircuitBreakerTest {

    private EndpointCircuitBreaker circuitBreaker;
    private UUID endpointId;

    @BeforeEach
    void setUp() {
        // 3 consecutive failures threshold, 1 second cooldown
        circuitBreaker = new EndpointCircuitBreaker(3, 1);
        endpointId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Circuit stays CLOSED during normal operation and occasional failures")
    void testNormalOperation() {
        assertThat(circuitBreaker.allowDelivery(endpointId)).isTrue();
        assertThat(circuitBreaker.getState(endpointId)).isEqualTo(EndpointCircuitBreaker.State.CLOSED);

        circuitBreaker.recordFailure(endpointId);
        circuitBreaker.recordFailure(endpointId);
        assertThat(circuitBreaker.allowDelivery(endpointId)).isTrue();

        circuitBreaker.recordSuccess(endpointId);
        assertThat(circuitBreaker.getState(endpointId)).isEqualTo(EndpointCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Circuit trips to OPEN after reaching failure threshold and recovers after cooldown")
    void testCircuitTrippingAndRecovery() throws InterruptedException {
        circuitBreaker.recordFailure(endpointId);
        circuitBreaker.recordFailure(endpointId);
        circuitBreaker.recordFailure(endpointId); // 3rd failure trips the breaker

        assertThat(circuitBreaker.getState(endpointId)).isEqualTo(EndpointCircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.allowDelivery(endpointId)).isFalse();

        // Wait for cooldown to expire
        Thread.sleep(1100);

        // State transitions to HALF_OPEN and allows 1 test probe
        assertThat(circuitBreaker.allowDelivery(endpointId)).isTrue();
        assertThat(circuitBreaker.getState(endpointId)).isEqualTo(EndpointCircuitBreaker.State.HALF_OPEN);

        // Successful probe closes the circuit
        circuitBreaker.recordSuccess(endpointId);
        assertThat(circuitBreaker.getState(endpointId)).isEqualTo(EndpointCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.allowDelivery(endpointId)).isTrue();
    }
}
