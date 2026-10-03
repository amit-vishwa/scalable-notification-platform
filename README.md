# Scalable Notification Platform

A production-oriented backend application for reliable, asynchronous
email notification processing.

## Current implementation — P006

Implemented:

- API-key authentication with application-scoped ownership
- Request validation before persistence
- Durable notification submission to MySQL
- Application-scoped idempotency and request fingerprinting
- Notification status lookup with masked recipient information
- Safe API error responses and correlation IDs
- Flyway migrations and automated tests

Not implemented yet:

- Background email delivery
- Provider integration
- Retry execution and recovery of interrupted work

A successful submission currently stores a notification as `PENDING`.
It does not send an email.

## Technology and architecture

- Java 17
- Spring Boot 4.1.1
- Maven Wrapper
- MySQL
- Spring Data JPA
- Flyway

The application is a single Maven module and deployable modular monolith.
The base package is `io.github.amitvishwa.notification`.

Package responsibilities:

- `api`: HTTP controllers, DTOs, authentication, and API errors
- `application`: submission, query, and idempotency use cases
- `domain`: notification models and lifecycle rules
- `infrastructure`: persistence and external integrations
- `config`: application configuration
- `worker`: background processing, implemented in a later milestone

## Prerequisites

- JDK 17
- Git
- Native MySQL Server
- An editor or IDE

Maven is downloaded through the repository's Maven Wrapper.
A separate Maven installation is not required.

Local development has been tested with MySQL 9.7.
CI uses MySQL 8.4 to verify compatibility with that supported baseline.

Docker Desktop is not required for local development.

## Database setup

Use separate databases and credentials for development and testing.
Never run automated tests against a production database.

For a new local installation, execute the following as a MySQL
administrator. Replace both password placeholders before executing.

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
migrations. Production migration and runtime privileges must be
separated before deployment.

Flyway manages the schema. Do not manually create application tables
or edit migrations that have already been applied.

## Build and test

Tests require MySQL to be running and the test database to exist.

In Git Bash, set the test password without displaying it:

```bash
read -r -s -p "Test database password: " NOTIFICATION_TEST_DB_PASSWORD
printf '\n'
export NOTIFICATION_TEST_DB_PASSWORD

./mvnw clean verify
```

Environment variables set this way apply only to the current terminal
session. Set them again after opening a new session.

On Windows PowerShell, after securely setting the same environment
variable:

```powershell
.\mvnw.cmd clean verify
```

The test profile supplies dummy application API keys. Do not replace
them with production credentials.

The build runs the tests and packages the executable application JAR.

## Run locally

Use the `local` Spring profile with the development database.

In Git Bash:

```bash
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
Do not commit real API keys, passwords, or environment files.

The application is available at `http://localhost:8080`.

Use `local` for the implemented API. The earlier `bootstrap` profile
is not a supported standalone API mode now that persistence-backed
services are present.

## API usage

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

The business key is `(sourceApplication, idempotencyKey)`.
The source application comes from the authenticated API key, not the
request body.

Business-key comparisons are case-sensitive. The same idempotency key
from two different applications represents two different notifications.

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

### Health

```bash
curl 'http://localhost:8080/actuator/health'
```

Only health endpoints are exposed through Actuator.
A healthy application does not imply email delivery is implemented.

## Error handling

- Missing or invalid API key: `401`
- Invalid request, malformed JSON, or invalid notification ID: `400`
- Missing or inaccessible notification: `404`
- Conflicting idempotency request: `409`
- Unexpected application failure: `500`

Errors use `application/problem+json`.
Responses include an error code and correlation ID.
Do not log API keys or unmasked request bodies.

## Continuous integration

GitHub Actions verifies pull requests targeting `main` and pushes to
`main` using Java 17, the Maven Wrapper, and a disposable MySQL service.

CI database credentials are dummy values used only by that temporary
service. No production secrets are required.

Run the complete build locally before requesting review.
Wait for the GitHub check to pass before merging.

## Contribution workflow

1. Update the local `main` branch.
2. Create a feature branch.
3. Implement changes and tests.
4. Run `./mvnw clean verify` and `git diff --check`.
5. Push the feature branch and open a pull request.
6. Review the changes and passing CI results before merging.

Do not push application changes directly to `main`.

## Planned development

- EmailProvider implementations
- Durable background processing
- Bounded retries and failure classification
- Recovery of interrupted processing
- Operational metrics and deployment hardening
- Separate worker deployment
- Redis and Kafka integration
- Kubernetes deployment

These are planned capabilities, not features of the current release.