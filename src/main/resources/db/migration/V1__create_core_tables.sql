CREATE TABLE transaction_logs (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL,
    customer_name VARCHAR(100) NOT NULL,
    account_number VARCHAR(20) NOT NULL UNIQUE,
    transaction_reference VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    transaction_type VARCHAR(20) NOT NULL,
    transaction_amount NUMERIC(19,2) NOT NULL,
    current_balance NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE transfer_transactions (
    id BIGSERIAL PRIMARY KEY,
    transfer_reference VARCHAR(255) NOT NULL UNIQUE,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    source_account_number VARCHAR(255) NOT NULL,
    destination_account_number VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    source_balance_after_transfer NUMERIC(19,2) NOT NULL,
    destination_balance_after_transfer NUMERIC(19,2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
