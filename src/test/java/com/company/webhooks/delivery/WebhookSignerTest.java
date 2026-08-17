package com.company.webhooks.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignerTest {

    private WebhookSigner signer;

    @BeforeEach
    void setUp() {
        signer = new WebhookSigner();
    }

    @Test
    @DisplayName("Compute and verify HMAC-SHA256 signature correctly")
    void testComputeAndVerifySignature() {
        byte[] payload = "{\"event\":\"invoice.paid\",\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        String secret = "whsec_test_secret_12345";

        String signature = signer.computeSignature(payload, secret);

        assertThat(signature).startsWith("sha256=");
        assertThat(signature.length()).isEqualTo(7 + 64); // "sha256=" + 64 hex characters

        boolean verified = signer.verifySignature(payload, secret, signature);
        assertThat(verified).isTrue();

        boolean corrupted = signer.verifySignature("tampered".getBytes(StandardCharsets.UTF_8), secret, signature);
        assertThat(corrupted).isFalse();
    }

    @Test
    @DisplayName("Null arguments throw IllegalArgumentException")
    void testNullArguments() {
        assertThatThrownBy(() -> signer.computeSignature(null, "secret"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> signer.computeSignature("test".getBytes(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
