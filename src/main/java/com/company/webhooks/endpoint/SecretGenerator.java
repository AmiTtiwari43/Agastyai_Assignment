package com.company.webhooks.endpoint;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class SecretGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateSecret() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        secureRandom.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }
}
