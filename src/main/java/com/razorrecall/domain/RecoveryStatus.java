package com.razorrecall.domain;

public enum RecoveryStatus {

    DETECTED,
    DECIDED,
    GUARDRAIL_CHECK,
    ACTION_PENDING,
    ACTION_EXECUTED,
    WAITING_FOR_OUTCOME,
    ABSTAINED,
    ESCALATED,
    RECOVERED,
    EXPIRED,
    ACTION_FAILED,
    STOPPED
}