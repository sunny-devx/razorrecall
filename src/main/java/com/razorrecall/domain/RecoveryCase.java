package com.razorrecall.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recovery_cases")
public class RecoveryCase {

    @Id
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "payment_attempt_id", nullable = false)
    private PaymentAttempt paymentAttempt;

    private String status;

    private String failureClass;

    private boolean eligible;

    private OffsetDateTime nextActionAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public RecoveryCase() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PaymentAttempt getPaymentAttempt() {
        return paymentAttempt;
    }

    public void setPaymentAttempt(PaymentAttempt paymentAttempt) {
        this.paymentAttempt = paymentAttempt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = failureClass;
    }

    public boolean isEligible() {
        return eligible;
    }

    public void setEligible(boolean eligible) {
        this.eligible = eligible;
    }

    public OffsetDateTime getNextActionAt() {
        return nextActionAt;
    }

    public void setNextActionAt(OffsetDateTime nextActionAt) {
        this.nextActionAt = nextActionAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}