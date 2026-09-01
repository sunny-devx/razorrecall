package com.razorrecall.service;

import com.razorrecall.domain.FailureClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FailureClassifierTest {

    private FailureClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new FailureClassifier();
    }

    @Test
    void testSoftFailureCodes() {
        FailureClassifier.ClassificationResult gatewayError = classifier.classify("GATEWAY_ERROR", "Gateway response timed out");
        assertEquals(FailureClass.SOFT, gatewayError.failureClass());
        assertTrue(gatewayError.eligible());

        FailureClassifier.ClassificationResult bankError = classifier.classify("BANK_TECHNICAL_ERROR", "Bank switch down");
        assertEquals(FailureClass.SOFT, bankError.failureClass());
        assertTrue(bankError.eligible());

        FailureClassifier.ClassificationResult timeout = classifier.classify("TIMEOUT", "Transaction timed out");
        assertEquals(FailureClass.SOFT, timeout.failureClass());
        assertTrue(timeout.eligible());
    }

    @Test
    void testHardFailureCodes() {
        FailureClassifier.ClassificationResult expired = classifier.classify("CARD_EXPIRED", "Card has expired");
        assertEquals(FailureClass.HARD, expired.failureClass());
        assertFalse(expired.eligible());

        FailureClassifier.ClassificationResult insufficientFunds = classifier.classify("INSUFFICIENT_FUNDS", "Not enough balance");
        assertEquals(FailureClass.HARD, insufficientFunds.failureClass());
        assertFalse(insufficientFunds.eligible());

        FailureClassifier.ClassificationResult fraud = classifier.classify("SUSPECTED_FRAUD", "High risk transaction");
        assertEquals(FailureClass.HARD, fraud.failureClass());
        assertFalse(fraud.eligible());
    }

    @Test
    void testUnknownFallback() {
        FailureClassifier.ClassificationResult unknown = classifier.classify("RANDOM_CUSTOM_CODE_99", "Some undocumented error");
        assertEquals(FailureClass.UNKNOWN, unknown.failureClass());
        assertFalse(unknown.eligible());
    }
}
