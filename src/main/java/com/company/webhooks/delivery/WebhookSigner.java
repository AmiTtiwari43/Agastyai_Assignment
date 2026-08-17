package com.company.webhooks.delivery;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class WebhookSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public String computeSignature(byte[] payloadBytes, String secret) {
        if (payloadBytes == null || secret == null) {
            throw new IllegalArgumentException("Payload bytes and secret cannot be null");
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(payloadBytes);
            return "sha256=" + HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    public boolean verifySignature(byte[] payloadBytes, String secret, String expectedSignature) {
        if (expectedSignature == null) {
            return false;
        }
        String calculated = computeSignature(payloadBytes, secret);
        return calculated.equalsIgnoreCase(expectedSignature.trim());
    }
}
