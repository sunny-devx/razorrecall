CREATE TABLE recovery_cases (
    id UUID PRIMARY KEY,
    payment_attempt_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    failure_class VARCHAR(50),
    eligible BOOLEAN NOT NULL,
    next_action_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_recovery_case_payment_attempt
        FOREIGN KEY (payment_attempt_id)
        REFERENCES payment_attempts(id)
);
