# Fund Transfer Assessment

This project implements the assessment brief with Spring Boot, PostgreSQL, JPA, Bean Validation, and Testcontainers.

## Project Purpose

The application exposes APIs to:

- create and fund customer accounts
- transfer funds between persisted customer accounts
- retrieve a customer's current account balance

## What You Need to Run This Project

- Java 21
- A reachable PostgreSQL instance
- Database credentials configured through environment variables or matching the defaults below
- Docker only if you want to start PostgreSQL with `docker compose`
- Docker or another Testcontainers-compatible container runtime if you want to run the integration tests

Default database settings:

- `DB_HOST=localhost`
- `DB_PORT=5432`
- `DB_NAME=fund_transfer`
- `DB_USERNAME=postgres`
- `DB_PASSWORD=postgres`

The application uses Flyway to create/update the schema at startup, so the main requirement is that PostgreSQL is running and the configured database is accessible.

## Reviewer Guide

1. Make sure PostgreSQL is running and matches the configured `DB_*` settings.
2. Start the application with `.\mvnw.cmd spring-boot:run`.
3. Create and fund Alice and Bob through `POST /api/v1/customer-accounts` with `Idempotency-Key`.
4. Transfer funds from Alice to Bob through `POST /api/v1/transfers` with `Idempotency-Key`.
5. Verify both balances through `GET /api/v1/customer-accounts/{accountNumber}/balance`.

## Available Endpoints

- `POST /api/v1/customer-accounts`
- `POST /api/v1/transfers`
- `GET /api/v1/customer-accounts/{accountNumber}/balance`

## Validation Rules

- Customer identifier and customer name are required.
- `Idempotency-Key` is required on both POST endpoints.
- Account numbers are normalized with `trim().toUpperCase()` and must be 3-20 alphanumeric characters.
- Transaction references are normalized with `trim().toUpperCase()` and must be 3-64 characters using letters, numbers, hyphens, or underscores.
- Funding amount must be zero or greater.
- Funding and transfer amounts are accepted and stored with at most 2 decimal places as `NUMERIC(19,2)`.
- API 1 persists new customer accounts as internal `CREDIT` funding records and computes `currentBalance` server-side from `transactionAmount`.
- Transfer amount must be greater than zero.
- Source and destination accounts must be different.

## Running the Application

Configure these environment variables first if you are not using the defaults:

```bash
DB_HOST=localhost
DB_PORT=5432
DB_NAME=fund_transfer
DB_USERNAME=postgres
DB_PASSWORD=postgres
```

If you want a quick local PostgreSQL instance, you can use Docker Compose:

```bash
docker compose up -d
```

Windows:

```bash
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Running Tests

Default suite:

```bash
.\mvnw.cmd test
```

Integration suite:

```bash
.\mvnw.cmd test -Pintegration-test
```

The integration profile uses Testcontainers and requires Docker or another Testcontainers-compatible container runtime.

## Sample Requests

### Create and fund customer account

```http
POST /api/v1/customer-accounts
Content-Type: application/json
Idempotency-Key: idem-customer-001

{
  "customerId": "CUST-001",
  "customerName": "Alice",
  "accountNumber": "ACC001",
  "transactionReference": "TXN-001",
  "transactionAmount": 5000.00
}
```

### Transfer funds

```http
POST /api/v1/transfers
Content-Type: application/json
Idempotency-Key: idem-transfer-001

{
  "sourceAccountNumber": "ACC001",
  "destinationAccountNumber": "ACC002",
  "amount": 1000.00,
  "transferReference": "TRF-001"
}
```

### Get current balance

```http
GET /api/v1/customer-accounts/ACC001/balance
```

## Important Design Decisions

- `transaction_logs` remains the primary persisted record for API 1 and the balance source for API 3 in this pass, even though it behaves as the current account state row rather than a multi-row ledger.
- `transfer_transactions` stores the result of each successful transfer.
- Both POST APIs persist `Idempotency-Key` and replay exact retries instead of creating duplicate side effects.
- Concurrent identical retries with the same `Idempotency-Key` replay the stored success response instead of surfacing a generic data conflict.
- Transfer retries using the same `Idempotency-Key` with a different normalized payload return `409 Conflict`.
- Transfers run in a single database transaction and use pessimistic locking with a stable account lock order.
- Controllers return DTOs rather than JPA entities.
- Flyway owns schema creation and Hibernate runs with `ddl-auto=validate`.

## Manual API Requests

Use [requests.http](/C:/Users/user/Downloads/fund-transfer-assessment/requests.http) or the Postman collection in [postman/Fund-Transfer-Assessment.postman_collection.json](/C:/Users/user/Downloads/fund-transfer-assessment/postman/Fund-Transfer-Assessment.postman_collection.json).
