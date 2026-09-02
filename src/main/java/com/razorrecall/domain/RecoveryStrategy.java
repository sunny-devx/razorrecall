package com.razorrecall.domain;

public enum RecoveryStrategy {

    SMART_RETRY,
    PAYMENT_LINK,
    CUSTOMER_NUDGE,
    ABSTAIN,
    MANUAL_ESCALATE
}
