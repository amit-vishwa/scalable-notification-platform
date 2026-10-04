# Scalable Notification Platform

A production-oriented, server-to-server notification backend with
durable, asynchronous processing.

The project is under development and is not yet production-ready.
The current provider simulates acceptance without sending emails.

## Current implementation — P007

Implemented:

- API-key authentication with application-scoped ownership
- Request validation before persistence
- Durable notification submission to MySQL
- Application-scoped idempotency and request fingerprinting
- Notification status lookup with masked recipient information
- Safe API error responses and correlation IDs
- Flyway-managed database migrations
- Durable background claims and delivery-attempt audit records
- Sequential processing through an internal EmailProvider interface
- A non-sending simulated provider restricted to local/test profiles
- Unit, configuration, and real-MySQL integration tests

Not implemented yet:

- Real email-provider adapters
- Retry execution, backoff, and retry exhaustion
- Recovery of interrupted or uncertain processing
- Production deployment and operational hardening

The worker is disabled by default. Submission stores a notification as
`PENDING`. Enabling the local simulated worker can advance it to `SENT`
without sending any email.

`SENT` currently represents simulated provider acceptance, not an email
sent or delivered to an inbox.

## Technology and architecture

- Java 17
- Spring Boot 4.1.1
- Maven Wrapper
- MySQL
- Spring Data JPA
- Flyway
- JUnit, Mockito, and AssertJ

The application is a single Maven module and deployable modular monolith.

Base package:

```text
io.github.amitvishwa.notification
```

Package responsibilities:

- `api`: HTTP controllers, DTOs, authentication, and API errors
- `application`: submission, query, idempotency, provider ports, and processing use cases
- `domain`: notification models and lifecycle rules
- `infrastructure`: persistence and provider implementations
- `config`: application configuration
- `worker`: scheduling of background polling

MySQL is the durable source of truth and the current work queue.
In-memory collections are not used as the primary notification store.

## Prerequisites

- JDK 17
- Git
- Native MySQL Server
- An editor or IDE

Use the repository's Maven Wrapper for reproducible builds.
A separate Maven installation is not required.

Local verification has been performed with MySQL 9.7.1.
CI is configured to verify the application against MySQL 8.4.

Docker Desktop is not required for local development or local testing.
The CI workflow uses its own disposable MySQL service.

## Database setup

Use separate databases and credentials for development and testing.
Never run automated tests against a production database.

For a new local installation, execute the following as a MySQL
administrator. Replace both password placeholders before execution.

```sql
CREATE DATABASE notification_platform
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE notification_platform_test
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'notification_user'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_YOUR_DEVELOPMENT_PASSWORD';

CREATE USER 'notification_test'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_YOUR_TEST_PASSWORD';

GRANT ALL PRIVILEGES ON notification_platform.*
    TO 'notification_user'@'localhost';

GRANT ALL PRIVILEGES ON notification_platform_test.*
    TO 'notification_test'@'localhost';
```

If these databases and users already exist, reuse them instead of
rerunning the creation statements.

These database-scoped grants support local development and Flyway
migrations. Before production deployment, separate migration privileges
from least-privilege application runtime access.

Flyway manages the schema. Do not manually create application tables
or edit migrations that have already been applied.

## Build and test

The complete test suite requires:

- Native MySQL running
- The dedicated `notification_platform_test` database
- The `NOTIFICATION_TEST_DB_PASSWORD` environment variable
- Empty `notification` and `delivery_attempt` tables before worker tests
- No other application using the test database during execution

Flyway's schema-history table can remain populated.

Worker integration tests refuse to clear pre-existing application records.
Their cleanup removes only fixtures created by the current test.

### Git Bash

Clear worker overrides and enter the test password without displaying it:

```bash
unset NOTIFICATION_WORKER_ENABLED NOTIFICATION_WORKER_POLL_ENABLED
unset NOTIFICATION_WORKER_BATCH_SIZE NOTIFICATION_WORKER_POLL_DELAY

read -r -s -p "Test database password: " NOTIFICATION_TEST_DB_PASSWORD
printf '\n'
export NOTIFICATION_TEST_DB_PASSWORD

./mvnw clean verify
```

Environment variables set this way apply only to the current terminal
session. Set them again after opening a new session.

### Windows PowerShell

After securely setting `NOTIFICATION_TEST_DB_PASSWORD` in the current
terminal, clear worker overrides and run:

```powershell
Remove-Item -LiteralPath `
    "Env:NOTIFICATION_WORKER_ENABLED", `
    "Env:NOTIFICATION_WORKER_POLL_ENABLED", `
    "Env:NOTIFICATION_WORKER_BATCH_SIZE", `
    "Env:NOTIFICATION_WORKER_POLL_DELAY" `
    -ErrorAction SilentlyContinue

.\mvnw.cmd clean verify
```

The test profile supplies dummy application API keys. Do not replace
them with production credentials.

The build runs the tests and packages the executable application JAR.

Expected constraint violations and rejected application contexts may
appear in negative-test logs. The final test summary must still report
zero failures and errors.

### Test coverage

The automated suite covers:

- Authentication, validation, ownership, and idempotency
- Request fingerprinting and domain lifecycle rules
- Persistence constraints and deterministic ordering
- Due-only, bounded notification claims
- Locked-row exclusion and claim rollback
- Committed claims before provider invocation
- Provider invocation outside database transactions
- Notification and delivery-attempt outcome persistence
- Unexpected failures and shutdown behaviour
- Worker enablement, polling gates, and configuration validation
- Simulator exclusion from production and bootstrap profiles

Worker integration tests explicitly disable scheduled polling and invoke
the processor directly. MySQL, repositories, and transaction services
remain real; the provider and clock are mocked.

## Run locally — API only

Use the `local` Spring profile with the development database.

In Git Bash:

```bash
unset NOTIFICATION_WORKER_ENABLED NOTIFICATION_WORKER_POLL_ENABLED
unset NOTIFICATION_WORKER_BATCH_SIZE NOTIFICATION_WORKER_POLL_DELAY

export DB_URL='jdbc:mysql://localhost:3306/notification_platform?serverTimezone=UTC'
export DB_USERNAME='notification_user'

read -r -s -p "Development database password: " DB_PASSWORD
printf '\n'
export DB_PASSWORD

read -r -s -p "Employee-service API key: " NOTIFICATION_EMPLOYEE_API_KEY
printf '\n'
export NOTIFICATION_EMPLOYEE_API_KEY

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Use a unique, randomly generated API key of 32–256 characters.
Each configured source application must have a distinct API key.

Do not commit real API keys, passwords, or environment files.

The application is available at:

```text
http://localhost:8080
```

The worker remains disabled in this mode. New submissions stay `PENDING`.

Use `local` for the implemented application. The earlier `bootstrap`
profile is not a supported standalone API mode now that persistence-backed
services are present.

## Durable worker — local simulation only

Enable simulation only against a disposable development database
containing dummy notifications.

Existing eligible `PENDING` records will also be processed. Never use
the test database or a production database for this demonstration.

After configuring development database credentials and the API key:

```bash
export NOTIFICATION_WORKER_ENABLED=true
export NOTIFICATION_WORKER_POLL_ENABLED=true
export NOTIFICATION_WORKER_BATCH_SIZE=10
export NOTIFICATION_WORKER_POLL_DELAY=PT5S

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The simulator sends no email and makes no provider network calls.

### Worker configuration

| Environment variable | Default | Behaviour |
| --- | --- | --- |
| `NOTIFICATION_WORKER_ENABLED` | `false` | Enables the processor |
| `NOTIFICATION_WORKER_POLL_ENABLED` | `true` | Enables scheduled polling when the worker is enabled |
| `NOTIFICATION_WORKER_BATCH_SIZE` | `10` | Accepts values from 1 to 10 |
| `NOTIFICATION_WORKER_POLL_DELAY` | `PT5S` | Accepts durations from 1 to 60 seconds |

Polling uses a fixed delay after the previous poll completes.
Processing is sequential within each worker instance.

### Claim and processing flow

Only `PENDING` notifications whose `nextAttemptAt` is due are eligible.

Eligible unlocked records are ordered by:

1. `nextAttemptAt`
2. `createdAtTime`
3. `notificationId`

A short `READ_COMMITTED` transaction uses `FOR UPDATE SKIP LOCKED` to
claim eligible records, change their status to `PROCESSING`, and insert
incomplete delivery attempts.

The claim transaction commits before provider invocation.

Provider calls run outside database transactions. A separate transaction
records each notification and delivery-attempt outcome together.

Concurrent claimers skip locked records. This protects claim exclusivity,
but does not guarantee strict global FIFO execution across worker instances.

### Provider outcomes

- `ACCEPTED`: notification becomes `SENT`
- `PERMANENT_FAILURE`: notification becomes `FAILED`
- `TEMPORARY_FAILURE`: notification becomes `RETRY_PENDING`, with a
  recorded one-minute delay

P007 does not consume `RETRY_PENDING`. Retry execution, backoff, and
exhaustion belong to P008.

The simulated provider is available only when `local` or `test` is active
and neither `prod` nor `bootstrap` is active.

Enabling the worker without an eligible `EmailProvider` causes startup
to fail.

### Current reliability limits

Crashes, shutdown, unexpected provider failures, or outcome-persistence
failures can leave notifications `PROCESSING` with incomplete attempts.

Claimed but not yet sent notifications can also remain `PROCESSING`
during shutdown. Recovery is planned for P008.

Provider acceptance followed by a failed database commit creates an
uncertain outcome. Do not blindly resend these notifications.

Exclusive database claims and submission idempotency do not guarantee
exactly-once email delivery.

After stopping the demonstration, clear worker overrides before testing
or returning to API-only development:

```bash
unset NOTIFICATION_WORKER_ENABLED NOTIFICATION_WORKER_POLL_ENABLED
unset NOTIFICATION_WORKER_BATCH_SIZE NOTIFICATION_WORKER_POLL_DELAY
```

## API usage

The API is intended for backend applications and accepts plain-text
notification content.

Set `NOTIFICATION_EMPLOYEE_API_KEY` in the terminal used for these
commands. A second terminal does not inherit values entered in the first.

### Submit a notification

```bash
curl -i 'http://localhost:8080/api/v1/notifications' \
  -H "X-API-Key: $NOTIFICATION_EMPLOYEE_API_KEY" \
  -H 'Content-Type: application/json' \
  --data '{
    "idempotencyKey": "employee-created-101",
    "recipientEmail": "recipient@example.com",
    "subject": "Welcome",
    "body": "Welcome to the application."
  }'
```

Submission outcomes:

- `202 Accepted`: a new notification has been committed to MySQL
- `200 OK`: an identical request returned the existing notification
- `409 Conflict`: the same business key was used with different content

The business key is:

```text
(sourceApplication, idempotencyKey)
```

The source application comes from the authenticated API key, not the
request body.

Business-key comparisons are case-sensitive. The same idempotency key
from two different applications represents two different notifications.

A conflicting request does not overwrite the original notification.
Acceptance confirms durable submission, not completed email delivery.

### Retrieve status

Replace `NOTIFICATION_ID` with the ID returned by submission:

```bash
curl -i \
  'http://localhost:8080/api/v1/notifications/NOTIFICATION_ID' \
  -H "X-API-Key: $NOTIFICATION_EMPLOYEE_API_KEY"
```

The response masks the recipient and omits the email body.

A missing notification or one owned by another application returns
`404 Not Found`.

Notification statuses are:

- `PENDING`
- `PROCESSING`
- `RETRY_PENDING`
- `SENT`
- `FAILED`

### Health

```bash
curl 'http://localhost:8080/actuator/health'
```

Only health endpoints are exposed through Actuator.

A healthy application does not prove provider availability or email
delivery. The current simulated provider sends no email.

## Error handling

- Missing or invalid API key: `401`
- Invalid request, malformed JSON, or invalid notification ID: `400`
- Missing or inaccessible notification: `404`
- Conflicting idempotency request: `409`
- Unexpected application failure: `500`

Errors use `application/problem+json`.
Responses include an error code and correlation ID.

Worker logs identify notifications and attempts without logging email
payloads. Unexpected provider errors are not automatically converted
into retryable failures.

Do not log API keys, database passwords, or unmasked request bodies.

## Security and production readiness

The current implementation is production-oriented, not production-ready.

Before production deployment, complete the remaining work, including:

- Real provider integration and bounded provider-call timeouts
- Retry execution and interrupted-work recovery
- Handling of uncertain provider outcomes
- HTTPS and deployment-specific security controls
- Secure secret management and API-key rotation
- Separate migration and least-privilege runtime database access
- Operational metrics, alerting, and deployment verification

Do not use the simulated provider as evidence of actual email delivery.

## Continuous integration

GitHub Actions is configured to verify pull requests targeting `main`
and pushes to `main` using Java 17, the Maven Wrapper, and a disposable
MySQL 8.4 service.

CI database credentials are dummy values used only by that temporary
service. No production secrets are required.

Run the complete build locally before requesting review.
The feature pull request must pass CI before merging.

## Contribution workflow

1. Update the local `main` branch.
2. Create a feature branch for the milestone or change.
3. Implement the changes and automated tests.
4. Run `./mvnw clean verify` and `git diff --check`.
5. Review and stage the intended changes.
6. Commit and push the feature branch.
7. Open a pull request targeting `main`.
8. Review the diff and passing CI results before merging.

Do not push application changes directly to `main`.
Do not commit credentials, generated build output, or local environment files.

## Planned development

- P008: retry execution, backoff, exhaustion, and interrupted-work recovery
- P009: real email-provider adapters and failure classification
- Operational metrics and deployment hardening
- Separate worker deployment
- Redis and Kafka integration
- Kubernetes deployment

These are planned capabilities, not features of the current implementation.