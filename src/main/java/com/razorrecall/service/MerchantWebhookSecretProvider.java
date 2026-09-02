package com.razorrecall.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MerchantWebhookSecretProvider {

    private final String defaultSecret;
    private final Map<String, String> merchantSecrets = new ConcurrentHashMap<>();

    public MerchantWebhookSecretProvider(
            @Value("${razorrecall.webhook.secret:test_webhook_secret_key_12345}") String defaultSecret
    ) {
        this.defaultSecret = defaultSecret;
    }

    public String getSecretForMerchant(String merchantId) {
        if (merchantId != null && !merchantId.isBlank() && merchantSecrets.containsKey(merchantId.trim())) {
            return merchantSecrets.get(merchantId.trim());
        }
        return defaultSecret;
    }

    public void registerMerchantSecret(String merchantId, String secret) {
        if (merchantId != null && secret != null) {
            merchantSecrets.put(merchantId.trim(), secret);
        }
    }

    public String getDefaultSecret() {
        return defaultSecret;
    }
}
