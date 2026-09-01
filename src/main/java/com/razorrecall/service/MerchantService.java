package com.razorrecall.service;

import com.razorrecall.domain.Merchant;
import com.razorrecall.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class MerchantService {

    public static final UUID DEFAULT_MERCHANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final MerchantRepository merchantRepository;

    public MerchantService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Transactional
    public Merchant getOrCreateDefaultMerchant() {
        return merchantRepository.findById(DEFAULT_MERCHANT_ID).orElseGet(() -> {
            Merchant defaultMerchant = new Merchant();
            defaultMerchant.setId(DEFAULT_MERCHANT_ID);
            defaultMerchant.setName("Platform Merchant");
            defaultMerchant.setCreatedAt(OffsetDateTime.now());
            return merchantRepository.save(defaultMerchant);
        });
    }

    @Transactional
    public Merchant resolveMerchant(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return getOrCreateDefaultMerchant();
        }

        String trimmedId = merchantId.trim();
        UUID uuid;
        try {
            uuid = UUID.fromString(trimmedId);
        } catch (IllegalArgumentException e) {
            uuid = UUID.nameUUIDFromBytes(trimmedId.getBytes(StandardCharsets.UTF_8));
        }

        UUID finalUuid = uuid;
        return merchantRepository.findById(finalUuid).orElseGet(() -> {
            Merchant newMerchant = new Merchant();
            newMerchant.setId(finalUuid);
            newMerchant.setName(trimmedId);
            newMerchant.setCreatedAt(OffsetDateTime.now());
            return merchantRepository.save(newMerchant);
        });
    }
}
