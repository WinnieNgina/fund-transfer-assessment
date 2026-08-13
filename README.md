# Fund Transfer Assessment

This project implements the assessment brief with Spring Boot, PostgreSQL, JPA, Bean Validation, and Testcontainers.

## Project Summary

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

## Reviewing the API

1. Make sure PostgreSQL is running and matches the configured `DB_*` settings.
2. Start the application.
3. Import the Postman collection from [postman/Fund-Transfer-Assessment.postman_collection.json](./postman/Fund-Transfer-Assessment.postman_collection.json).
4. Run the requests in the collection to verify the happy path, idempotency behavior, validation failures, and error scenarios.

If you prefer editor-based requests, you can use [requests.http](./requests.http) instead.

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

## Reference

Available endpoints:

- `POST /api/v1/customer-accounts`
- `POST /api/v1/transfers`
- `GET /api/v1/customer-accounts/{accountNumber}/balance`

Manual API resources:

- [postman/Fund-Transfer-Assessment.postman_collection.json](./postman/Fund-Transfer-Assessment.postman_collection.json)
- [requests.http](./requests.http)
