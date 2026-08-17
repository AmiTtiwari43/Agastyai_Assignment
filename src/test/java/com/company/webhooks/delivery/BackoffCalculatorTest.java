package com.company.webhooks.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    private BackoffCalculator backoffCalculator;

    @BeforeEach
    void setUp() {
        // Base delay = 30s, Max delay = 21600s (6h), Jitter = 15%
        backoffCalculator = new BackoffCalculator(30, 21600, 0.15);
    }

    @Test
    @DisplayName("Boundary Case: Attempt 0 and Attempt 1 calculate base delay of 30 seconds")
    void testAttemptZeroAndOne() {
        Duration delay0 = backoffCalculator.calculateDeterministicDelay(0);
        Duration delay1 = backoffCalculator.calculateDeterministicDelay(1);

        assertThat(delay0).isEqualTo(Duration.ofSeconds(30));
        assertThat(delay1).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("NFR-6: Delay strictly increases exponentially across attempts prior to jitter")
    void testExponentialGrowth() {
        Duration delay1 = backoffCalculator.calculateDeterministicDelay(1); // 30s
        Duration delay2 = backoffCalculator.calculateDeterministicDelay(2); // 60s
        Duration delay3 = backoffCalculator.calculateDeterministicDelay(3); // 120s
        Duration delay4 = backoffCalculator.calculateDeterministicDelay(4); // 240s
        Duration delay5 = backoffCalculator.calculateDeterministicDelay(5); // 480s
        Duration delay6 = backoffCalculator.calculateDeterministicDelay(6); // 960s
        Duration delay7 = backoffCalculator.calculateDeterministicDelay(7); // 1920s
        Duration delay8 = backoffCalculator.calculateDeterministicDelay(8); // 3840s

        assertThat(delay1.toSeconds()).isEqualTo(30);
        assertThat(delay2.toSeconds()).isEqualTo(60);
        assertThat(delay3.toSeconds()).isEqualTo(120);
        assertThat(delay4.toSeconds()).isEqualTo(240);
        assertThat(delay5.toSeconds()).isEqualTo(480);
        assertThat(delay6.toSeconds()).isEqualTo(960);
        assertThat(delay7.toSeconds()).isEqualTo(1920);
        assertThat(delay8.toSeconds()).isEqualTo(3840);

        // Verify strictly increasing
        assertThat(delay2).isGreaterThan(delay1);
        assertThat(delay3).isGreaterThan(delay2);
        assertThat(delay4).isGreaterThan(delay3);
        assertThat(delay5).isGreaterThan(delay4);
        assertThat(delay6).isGreaterThan(delay5);
        assertThat(delay7).isGreaterThan(delay6);
        assertThat(delay8).isGreaterThan(delay7);
    }

    @Test
    @DisplayName("Cap Enforcement: Delay does not exceed max delay (21600 seconds)")
    void testMaxDelayCap() {
        Duration delayLarge = backoffCalculator.calculateDeterministicDelay(50);
        assertThat(delayLarge.toSeconds()).isEqualTo(21600);
    }

    @Test
    @DisplayName("Jitter Bounds: Jitter stays strictly within configured percentage (+/- 15%)")
    void testJitterBounds() {
        int attempt = 4; // Deterministic = 240s, 15% = 36s -> range [204s, 276s]
        long minExpected = Math.round(240 * (1 - 0.15));
        long maxExpected = Math.round(240 * (1 + 0.15));

        for (int i = 0; i < 100; i++) {
            Duration jittered = backoffCalculator.calculateDelayWithJitter(attempt);
            assertThat(jittered.toSeconds())
                    .isGreaterThanOrEqualTo(minExpected)
                    .isLessThanOrEqualTo(maxExpected);
        }
    }
}
