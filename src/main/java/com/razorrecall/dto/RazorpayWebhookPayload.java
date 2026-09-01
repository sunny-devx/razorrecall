package com.razorrecall.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class RazorpayWebhookPayload {

    private static final ObjectMapper DEFAULT_MAPPER = JsonMapper.builder().build();

    private String eventType;
    private String paymentId;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String errorCode;
    private String errorReason;
    private String merchantId;

    public RazorpayWebhookPayload() {
    }

    public static RazorpayWebhookPayload parse(String json) {
        return parse(json, DEFAULT_MAPPER);
    }

    public static RazorpayWebhookPayload parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Payload cannot be empty");
        }

        ObjectMapper mapper = objectMapper != null ? objectMapper : DEFAULT_MAPPER;

        try {
            JsonNode root = mapper.readTree(json);
            RazorpayWebhookPayload payload = new RazorpayWebhookPayload();

            // Extract event type
            if (root.hasNonNull("event")) {
                payload.eventType = root.get("event").asText();
            } else if (root.hasNonNull("event_type")) {
                payload.eventType = root.get("event_type").asText();
            }

            // Extract nested payment entity if present
            JsonNode paymentEntity = null;
            if (root.has("payload") && root.get("payload").has("payment") && root.get("payload").get("payment").has("entity")) {
                paymentEntity = root.get("payload").get("payment").get("entity");
            }

            // Extract payment ID
            if (paymentEntity != null && paymentEntity.hasNonNull("id")) {
                payload.paymentId = paymentEntity.get("id").asText();
            } else if (root.hasNonNull("payment_id")) {
                payload.paymentId = root.get("payment_id").asText();
            } else if (root.hasNonNull("id")) {
                payload.paymentId = root.get("id").asText();
            }

            // Extract order ID
            if (paymentEntity != null && paymentEntity.hasNonNull("order_id")) {
                payload.orderId = paymentEntity.get("order_id").asText();
            } else if (root.hasNonNull("order_id")) {
                payload.orderId = root.get("order_id").asText();
            }

            // Extract amount
            if (paymentEntity != null && paymentEntity.hasNonNull("amount")) {
                long amountInPaise = paymentEntity.get("amount").asLong();
                payload.amount = BigDecimal.valueOf(amountInPaise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else if (root.hasNonNull("amount")) {
                if (root.get("amount").isNumber()) {
                    payload.amount = BigDecimal.valueOf(root.get("amount").asDouble()).setScale(2, RoundingMode.HALF_UP);
                } else {
                    payload.amount = new BigDecimal(root.get("amount").asText()).setScale(2, RoundingMode.HALF_UP);
                }
            } else {
                payload.amount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }

            // Extract currency
            if (paymentEntity != null && paymentEntity.hasNonNull("currency")) {
                payload.currency = paymentEntity.get("currency").asText();
            } else if (root.hasNonNull("currency")) {
                payload.currency = root.get("currency").asText();
            } else {
                payload.currency = "INR";
            }

            // Extract error code
            if (paymentEntity != null && paymentEntity.hasNonNull("error_code")) {
                payload.errorCode = paymentEntity.get("error_code").asText();
            } else if (root.hasNonNull("error_code")) {
                payload.errorCode = root.get("error_code").asText();
            }

            // Extract error reason / description
            if (paymentEntity != null) {
                if (paymentEntity.hasNonNull("error_description")) {
                    payload.errorReason = paymentEntity.get("error_description").asText();
                } else if (paymentEntity.hasNonNull("error_reason")) {
                    payload.errorReason = paymentEntity.get("error_reason").asText();
                }
            }
            if (payload.errorReason == null) {
                if (root.hasNonNull("failure_reason")) {
                    payload.errorReason = root.get("failure_reason").asText();
                } else if (root.hasNonNull("error_reason")) {
                    payload.errorReason = root.get("error_reason").asText();
                } else if (root.hasNonNull("error_description")) {
                    payload.errorReason = root.get("error_description").asText();
                }
            }

            // Extract merchant ID
            if (paymentEntity != null && paymentEntity.has("notes") && paymentEntity.get("notes").hasNonNull("merchant_id")) {
                payload.merchantId = paymentEntity.get("notes").get("merchant_id").asText();
            } else if (root.hasNonNull("merchant_id")) {
                payload.merchantId = root.get("merchant_id").asText();
            } else if (root.hasNonNull("account_id")) {
                payload.merchantId = root.get("account_id").asText();
            }

            if (payload.eventType == null || payload.eventType.isBlank()) {
                throw new IllegalArgumentException("Missing webhook event type");
            }
            if (payload.paymentId == null || payload.paymentId.isBlank()) {
                throw new IllegalArgumentException("Missing payment_id");
            }

            return payload;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse webhook JSON payload", e);
        }
    }

    public String getEventType() {
        return eventType;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public String getMerchantId() {
        return merchantId;
    }
}
