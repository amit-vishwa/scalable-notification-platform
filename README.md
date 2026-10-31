# Scalable Notification Platform

A production-oriented, server-to-server notification backend with durable,
asynchronous processing.

The project is under development and is **not yet production-ready**.
Email sending supports selectable simulator and SMTP adapters. The
simulator does not send email; the SMTP adapter submits to a configured
SMTP server.

## Current implementation — P010

Implemented:

- API-key authentication with application-scoped ownership
- Request validation before persistence
- Durable submission to MySQL before returning a new-request acceptance
- Application-scoped idempotency and request fingerprinting
- Status lookup with masked recipient information
- Safe API error responses and correlation IDs
- Flyway-managed database migrations
- Durable background claims and delivery-attempt history
- Sequential processing through an internal `EmailProvider` interface
- Scheduled retries with capped exponential backoff and attempt exhaustion
- Processing leases and conservative interrupted-work recovery
- Audited, opt-in operator replay through a non-web command-line mode
- A non-sending simulator restricted to eligible local/test configurations
- Selectable SMTP adapter without changing the worker's provider contract
- Validated SMTP/TLS settings and finite connection/read/write timeouts
- UTF-8 single-recipient plaintext MIME and header-injection rejection
- Conservative SMTP failure classification and safe internal failure codes
- Versioned, application-scoped email preferences with explicit opt-in
- Daily named-timezone quiet hours and durable deferral
- Terminal suppression with distinct preference-related reasons
- Preference checks before delivery-attempt creation
- Protection against recipient fragments in raw Hibernate JDBC logs
- Unit, configuration, real-MySQL integration, and explicit upgrade-rehearsal tests

Still to implement or verify:

- External SMTP-service validation and mailbox-delivery feedback
- Combined P010 preference-to-live-SMTP demonstration
- Populated V4-to-V5 upgrade rehearsal
- Production authorization, secrets, deployment, observability, and hardening
- Representative query-plan, lock-contention, and multi-worker throughput testing
- Separate worker deployment, Redis, Kafka, and Kubernetes

The default operating scope remains one deployment with one sequential
scheduled worker. The worker is disabled by default. New submissions stay
`PENDING` until processing is enabled.

When processing is enabled, an explicit enabled preference must exist
for the exact source application and recipient. After retry-exhaustion
checks, missing or disabled preferences suppress eligible work; quiet
hours defer it.

See the [email preferences guide](docs/email-preferences.md) for the API,
claim-time semantics and rollout requirements.

`SENT` means provider acceptance, not confirmed mailbox delivery.
For the simulator this is simulated acceptance. For SMTP it means the
submission completed successfully. Mailpit capture proves local SMTP
receipt, not delivery to an external mailbox.

## Technology and architecture

- Java 17
- Spring Boot 4.1.1
- Maven Wrapper
- MySQL running natively or in Docker locally; MySQL 8.4 in CI
- Spring Data JPA and Flyway
- JUnit, Mockito, and AssertJ

The application is one Maven module and a deployable modular monolith.

Base package: `io.github.amitvishwa.notification`

| Package | Responsibility |
| --- | --- |
| `api` | HTTP controllers, DTOs, authentication, and API errors |
| `application` | Submission, queries, preferences, idempotency, provider ports, processing, and replay use cases |
| `domain` | Notification/preference models, lifecycle rules, quiet hours, and retry policy |
| `infrastructure` | Persistence and provider implementations |
| `config` | Application configuration and startup safety gates |
| `worker` | Scheduled background polling |
| `operator.replay` | Explicit operator CLI argument parsing and execution |

MySQL is the durable source of truth and the current work queue.
In-memory collections are not the primary notification store.

## Prerequisites

- JDK 17
- Git
- MySQL Server, either native or containerized
- An editor or IDE

Use the repository's Maven Wrapper; a separate Maven installation is not
required. P009 local verification used MySQL 8.4.13 in Docker. Earlier
local verification used native MySQL 9.7; CI uses MySQL 8.4.

Docker Desktop is optional when using native MySQL. The
[local SMTP capture guide](docs/local-smtp-demo.md) uses Mailpit in Docker.
Standard SMTP unit/configuration tests do not require a live SMTP server.

### Windows toolchain verification

Install JDK 17 and set `JAVA_HOME` to the JDK installation directory,
not its `bin` subdirectory. Add `%JAVA_HOME%\bin` to `PATH`.

After changing environment variables, restart terminals and IDEs so
they receive the updated values.

From the repository root, verify the Java runtime and compiler:

```text
java -version
javac -version
```

Both commands should report Java 17.

Verify the Maven Wrapper using the command for your shell.

PowerShell:

```powershell
.\mvnw.cmd --version
```

Git Bash:

```bash
./mvnw --version
```

Maven should report Java 17 and the Maven version configured in
`.mvn/wrapper/maven-wrapper.properties`. The first wrapper invocation
requires internet access to download Maven if it is not already cached.

These version checks do not build the application, run tests, start
the worker, or require MySQL. Database-backed execution and integration
tests require the separate database setup below.

## Local database setup

The account hosts and SQL examples in this section describe native MySQL.
The reviewed Windows-to-container setup instead uses
`notification_user@'%'` and `notification_test@'%'`, with the MySQL host
port bound only to `127.0.0.1:3306`.

For a fresh container setup, create those two dedicated accounts with
`@'%'` and apply the same exact table-level grants to those identities.
Do not run the native `@'localhost'` account statements unchanged for
that setup, alter existing accounts, or replace account hosts throughout
the runbooks. The separate opt-in upgrade rehearsal retains its
`notification_upgrade_test@localhost` identity requirement.

Keep development and automated-test databases/accounts separate:

| Purpose | Database | Native MySQL account |
| --- | --- | --- |
| Development | `notification_platform` | `notification_user@localhost` |
| Automated tests | `notification_platform_test` | `notification_test@localhost` |
| Explicit upgrade rehearsal only | `notification_platform_upgrade_test` | `notification_upgrade_test@localhost` |

Never run tests or demonstrations against production data.

If development/test databases already exist, use the approved existing
setup. Do not rerun account creation, change grants, or clear existing data
without reviewing their current use.

For a genuinely new local setup, connect as a MySQL administrator. First
check the exact names; if any exist, stop this fresh-setup sequence.

```sql
SELECT SCHEMA_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('notification_platform', 'notification_platform_test');

SELECT User, Host
FROM mysql.user
WHERE User IN ('notification_user', 'notification_test');
```

Only when both results are empty, run each following statement separately,
stopping on errors:

```sql
CREATE DATABASE notification_platform
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE notification_platform_test
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'notification_user'@'localhost' IDENTIFIED BY RANDOM PASSWORD;

CREATE USER 'notification_test'@'localhost' IDENTIFIED BY RANDOM PASSWORD;
```

Each account-creation result contains its generated password. Store it
privately; do not share the result or save credentials in the repository.

Grant these exact table privileges; Flyway will create the tables:

```sql
GRANT ALL PRIVILEGES ON
    `notification_platform`.`notification`
    TO 'notification_user'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform`.`delivery_attempt`
    TO 'notification_user'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform`.`notification_replay`
    TO 'notification_user'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform`.`email_preference`
    TO 'notification_user'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform`.`flyway_schema_history`
    TO 'notification_user'@'localhost';

GRANT ALL PRIVILEGES ON
    `notification_platform_test`.`notification`
    TO 'notification_test'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform_test`.`delivery_attempt`
    TO 'notification_test'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform_test`.`notification_replay`
    TO 'notification_test'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform_test`.`email_preference`
    TO 'notification_test'@'localhost';
GRANT ALL PRIVILEGES ON
    `notification_platform_test`.`flyway_schema_history`
    TO 'notification_test'@'localhost';
```

Verify each account with `SHOW GRANTS`. These are local migration/test
privileges, not a production runtime privilege design. Production must
separate migration, application runtime, and operator access.

Exact-table grants avoid schema-name wildcard ambiguity and must be
reviewed when future migrations add tables. MySQL permits grants before
table creation when CREATE is included. See [MySQL GRANT](https://dev.mysql.com/doc/refman/9.7/en/grant.html).

Flyway owns schema changes. Do not manually create application tables or
edit migrations already applied to a database.

## Build and test

The standard suite requires:

- MySQL running, natively or in Docker
- `notification_platform_test` and its dedicated account
- `NOTIFICATION_TEST_DB_PASSWORD` in the current terminal
- Empty `notification`, `delivery_attempt`, and `notification_replay` tables
- No running application/worker or other test using that database

The Flyway schema-history table can remain populated. Queue/replay
integration tests refuse pre-existing application records and clean only
their own fixtures.

### Git Bash

Run password-entry commands one at a time, entering the password when
prompted. Input characters are intentionally hidden.

```bash
unset NOTIFICATION_WORKER_ENABLED NOTIFICATION_WORKER_POLL_ENABLED
unset NOTIFICATION_WORKER_BATCH_SIZE NOTIFICATION_WORKER_POLL_DELAY
unset NOTIFICATION_UPGRADE_REHEARSAL NOTIFICATION_UPGRADE_DB_PASSWORD

set +x
IFS= read -r -s -p "Test database password: " NOTIFICATION_TEST_DB_PASSWORD
printf '\n'
export NOTIFICATION_TEST_DB_PASSWORD

./mvnw clean verify
```

Clear any additional custom retry/worker overrides before expecting
default-configuration test results. Use a dedicated, reviewed test shell;
unrelated datasource/JVM overrides must not redirect application tests.

Environment variables entered in a terminal are session-specific. A
separate or newly opened terminal does not inherit later changes.

### Windows PowerShell

After securely setting `NOTIFICATION_TEST_DB_PASSWORD` for the current
terminal and clearing custom configuration overrides:

```powershell
Remove-Item -LiteralPath `
    "Env:NOTIFICATION_WORKER_ENABLED", `
    "Env:NOTIFICATION_WORKER_POLL_ENABLED", `
    "Env:NOTIFICATION_WORKER_BATCH_SIZE", `
    "Env:NOTIFICATION_WORKER_POLL_DELAY", `
    "Env:NOTIFICATION_UPGRADE_REHEARSAL", `
    "Env:NOTIFICATION_UPGRADE_DB_PASSWORD" `
    -ErrorAction SilentlyContinue

.\mvnw.cmd clean verify
```

The test profile supplies dummy API keys; do not replace them with real
credentials. Negative tests exercise expected database constraint failures
and refused application contexts. Raw Hibernate JDBC error/warning
messages are disabled to avoid exposing recipient-derived key values.
The final summary must have zero unexpected failures/errors.

SMTP adapter/configuration tests use synthetic messages, mocked senders
or disconnected configuration objects. The standard CI suite does not
start Mailpit or verify real SMTP transport. The local capture
demonstration is a separate verification step.

### Explicit populated-schema upgrade rehearsal

`NotificationMigrationUpgradeRehearsal` is deliberately outside Surefire's
default test-name patterns and additionally requires an environment opt-in.
It is not part of the normal CI suite and uses a separate, freshly created
database/account.

Follow [the upgrade rehearsal guide](docs/upgrade-rehearsal.md). It applies
V1/V2, inserts synthetic historical data, applies V3/V4, validates all old
fields and new counters, and verifies a repeated migrate is a no-op.
It never cleans, repairs, baselines, or automatically resets a database.

This historical rehearsal verifies V2 to V4 only; it does not verify V5.

### Verification evidence — 17 October 2026

Local results reported for the P008 feature branch:

- Standard `clean verify`: 176 tests, zero failures/errors/skips
- Packaged operator CLI: successful replay, unchanged identical repeat,
  and rejected conflicting reuse
- Smoke-fixture cleanup committed; normal test tables returned to 0/0/0
- Explicit populated upgrade rehearsal: one test, zero failures/errors/skips;
  V2 to V4; four notifications/four attempts preserved; zero replay audits;
  repeated migrate executed zero migrations

The upgrade rehearsal is additional to, not included in, the 176-test
standard run. These results do not establish real email delivery,
production authorization, production rollback, or throughput. P008 PR
review and its CI result remain separate completion gates.

### Verification evidence — 25 October 2026

Local results reported for the P009 feature branch:

- Fresh full `clean verify`: 248 tests, zero failures/errors/skips
- Executable JAR packaged successfully
- Local Mailpit demonstration: POST returned `202`, persisted status reached
  `SENT`, and one message was captured
- An identical repeat returned `200` with the same notification ID

These are local results. PR review and CI are separate delivery gates.
SMTP capture does not establish external inbox delivery, production
readiness, throughput or exactly-once delivery.

### P010 verification status — 31 October 2026

- Final post-review `clean verify`: 452 tests, zero
  failures/errors/skips; completed at 22:14:33 IST.
- Executable JAR packaged successfully after the operator-configuration
  refinement.
- Coverage includes real-MySQL preference/API/worker tests, concurrent
  creation and updates, suppression status, logging privacy and all
  30 operator-safety cases.
- PR review, CI and merge remain separate delivery gates.

These results do not establish a populated V4-to-V5 upgrade or a rerun of
the combined P010 preference-to-live-SMTP demonstration. Historical P008
and P009 evidence remains separate.

## Run locally — API only

Use the `local` profile and the development database. In Git Bash, run
password/API-key prompts individually:

```bash
unset NOTIFICATION_WORKER_ENABLED NOTIFICATION_WORKER_POLL_ENABLED
unset NOTIFICATION_WORKER_BATCH_SIZE NOTIFICATION_WORKER_POLL_DELAY

export DB_URL='jdbc:mysql://localhost:3306/notification_platform?serverTimezone=UTC'
export DB_USERNAME='notification_user'

set +x
IFS= read -r -s -p "Development database password: " DB_PASSWORD
printf '\n'
export DB_PASSWORD

IFS= read -r -s -p "Local employee-service API key: " NOTIFICATION_EMPLOYEE_API_KEY
printf '\n'
export NOTIFICATION_EMPLOYEE_API_KEY

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Use a unique randomly generated API key of 32–256 characters. Each source
application must have a distinct key. Never commit real passwords, keys,
or environment files; do not rely on local YAML's fallback password.

The API listens at `http://localhost:8080`. With the worker disabled, new
submissions remain `PENDING`.

The earlier `bootstrap` profile is not a supported standalone API mode
now that persistence-backed services are present.

## Durable worker and email providers

The following commands use the non-sending simulator. For an SMTP
demonstration, follow the
[local SMTP capture guide](docs/local-smtp-demo.md).

Use a disposable development dataset containing only dummy notifications,
not the automated-test database. Enabling the worker can process existing
due `PENDING` and `RETRY_PENDING` records and recover expired
`PROCESSING` records.

After configuring development credentials and a local API key:

```bash
export NOTIFICATION_EMAIL_PROVIDER=simulated
export NOTIFICATION_WORKER_ENABLED=true
export NOTIFICATION_WORKER_POLL_ENABLED=true
export NOTIFICATION_WORKER_BATCH_SIZE=10
export NOTIFICATION_WORKER_POLL_DELAY=PT5S

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The simulator always returns acceptance without sending email or making a
provider network call. Temporary/permanent/unknown outcomes are exercised
by tests, not by the acceptance-only local simulator.

### Email provider settings

The worker depends on the `EmailProvider` strategy. The SMTP adapter
translates that contract to Spring/Jakarta Mail; configuration selects
the implementation without rewriting processing or persistence.

Use canonical Spring property names:

| Property | Default | Requirement |
| --- | --- | --- |
| `notification.email.provider` | `SIMULATED` | `SIMULATED` or `SMTP` |
| `notification.email.smtp.host` | None | Required for active SMTP |
| `notification.email.smtp.port` | `587` | 1–65535; configure the intended port explicitly |
| `notification.email.smtp.from-address` | None | One valid plain mailbox |
| `notification.email.smtp.security` | `STARTTLS` | `PLAIN`, `STARTTLS` or `SMTPS` |
| `notification.email.smtp.authentication` | `false` | Enables SMTP authentication |
| `notification.email.smtp.username` | None | Required with authentication; absent otherwise |
| `notification.email.smtp.password` | None | Required with authentication; absent otherwise |
| `notification.email.smtp.connection-timeout` | `PT5S` | 1–30 seconds |
| `notification.email.smtp.read-timeout` | `PT5S` | 1–30 seconds |
| `notification.email.smtp.write-timeout` | `PT5S` | 1–30 seconds |

SMTP properties are bound only when SMTP is selected and the worker is
enabled, outside bootstrap/operator-replay modes. Unknown provider values
fail startup when provider selection is active. The simulator remains
restricted to eligible local/test profiles.

PLAIN requires an explicit loopback host and an eligible local/test
profile without prod. Authentication requires credentials and encrypted
transport. STARTTLS is required rather than opportunistic; encrypted
modes verify server identity. SMTPS does not automatically change the
configured port. Bootstrap and operator-replay modes do not activate SMTP.

Use only `notification.email.*` for this provider configuration.
Unrelated `spring.mail.*` settings can independently enable Spring
Boot's standard mail auto-configuration.

The SMTP adapter sends one UTF-8 plaintext message. Its successful result
does not invent a provider queue identifier from the MIME Message-ID.

Recognized socket-connection failures and unambiguous SMTP 4xx rejections
are temporary failures. Explicit 5xx rejections and invalid messages are
permanent failures. Authentication failures follow a fail-fast
operator-correction policy.

Ambiguous transmission/cleanup failures, partial acceptance, conflicting
evidence and unsafe exception chains remain UNKNOWN and are not
automatically retried. The SMTP unknown code is stored on the delivery
attempt as `SMTP_DELIVERY_OUTCOME_UNKNOWN`; the notification/status API
uses `DELIVERY_OUTCOME_UNKNOWN`.

### Worker and retry settings

| Environment variable | Default | Validated range/meaning |
| --- | --- | --- |
| `NOTIFICATION_WORKER_ENABLED` | `false` | Enables the processor |
| `NOTIFICATION_WORKER_POLL_ENABLED` | `true` | Enables polling only when the worker is enabled |
| `NOTIFICATION_WORKER_BATCH_SIZE` | `10` | 1–10 |
| `NOTIFICATION_WORKER_POLL_DELAY` | `PT5S` | 1 second–1 minute |
| `NOTIFICATION_RETRY_MAX_ATTEMPTS` | `5` | 1–100 total claimed attempts per cycle, including the initial one |
| `NOTIFICATION_RETRY_INITIAL_DELAY` | `PT1M` | 1 second–24 hours |
| `NOTIFICATION_RETRY_MAX_DELAY` | `PT15M` | 1 second–24 hours; at least the initial delay |
| `NOTIFICATION_RETRY_PROCESSING_LEASE` | `PT5M` | 30 seconds–1 hour |

Polling is sequential on one configured scheduler thread. Initial delay
and fixed delay use the poll-delay setting; the fixed delay starts after
the previous poll finishes.

Default temporary-failure retry delays are **1, 2, 4, and 8 minutes**.
The five-attempt default permits four retries, so its 15-minute cap is not
reached. Backoff has no jitter; due times are persisted in MySQL instead
of sleeping a worker thread.

### Processing and outcomes

Each batch first recovers expired claims, then claims due work. Claim
selection orders eligible unlocked rows by:

1. `nextAttemptAt`
2. `createdAtTime`
3. `notificationId`

After checking retry exhaustion, the worker evaluates the current
preference for the exact source application and recipient at the batch's
claim timestamp. Missing/disabled preferences suppress work without a new
attempt. Quiet hours persist a future due time without consuming retry
budget. Only an explicit allow decision creates a delivery attempt.

Preference changes affect subsequent eligible claims, not already-claimed
work, and do not pull forward an existing `nextAttemptAt`.

Short `REQUIRES_NEW / READ_COMMITTED` transactions handle recovery,
claims, pre-send checks, and outcome persistence. Claims commit their
`PROCESSING` status, counters, lease, and incomplete attempt before a
provider is called. No database transaction spans the provider call.

| Provider outcome | Notification result |
| --- | --- |
| `ACCEPTED` | `SENT` |
| `TEMPORARY_FAILURE` with budget remaining | `RETRY_PENDING` with a future due time |
| `TEMPORARY_FAILURE` with exhausted budget | `FAILED / RETRY_EXHAUSTED` |
| `PERMANENT_FAILURE` | `FAILED` with the safe provider failure code |
| `UNKNOWN` | `FAILED / DELIVERY_OUTCOME_UNKNOWN` |

A null provider result or provider `RuntimeException` is treated as
unknown, not automatically retryable. Processing/database failures abort
the batch rather than being misclassified as provider failures. The
scheduler logs the failed poll and can poll again later.

Counters have different meanings:

- `last_attempt_number`: lifetime attempt sequence, incremented at claim;
  retained across manual replay. Sparse legacy history means it is not
  necessarily the number of attempt rows.
- `cycle_attempt_count`: claimed attempts in the current cycle, including
  claims that might never reach the provider; reset by manual replay.
- `retry_count`: cumulative scheduled automatic retries, not total sends;
  retained across manual replay.

### Recovery and limits

Expired incomplete claims are closed as unknown and marked
`FAILED / DELIVERY_OUTCOME_UNKNOWN`. Missing current attempt history
produces `FAILED / ATTEMPT_HISTORY_MISSING`. Legacy NULL leases use a
last-attempt cutoff. Existing closed/partially closed history is preserved.

Recovery does **not** automatically resend uncertain delivery. It runs
when the batch processor runs; disabling the worker also stops its
scheduled recovery.

All claims in a batch share an initial expiry. Queue wait and provider
execution consume that lease. There is currently no lease renewal or
heartbeat. The SMTP adapter has finite connection/read/write timeouts,
but these do not establish a strict whole-send deadline or guarantee
completion within a processing lease.

`SKIP LOCKED` supports exclusive claims, not strict global FIFO or an
exact lock-footprint bound. Query-plan/contention testing is required
before scaling to multiple workers. See [MySQL locking reads](https://dev.mysql.com/doc/refman/9.7/en/innodb-locking-reads.html)
and [InnoDB lock scope](https://dev.mysql.com/doc/refman/9.7/en/innodb-locks-set.html).

A lease cannot cancel/fence an external sender. Graceful shutdown stops
new work when observed but cannot promise cancellation of an in-flight
provider call. Submission idempotency and exclusive claims do **not**
guarantee exactly-once email delivery.

Clear worker overrides after stopping a demonstration:

```bash
unset NOTIFICATION_EMAIL_PROVIDER
unset NOTIFICATION_WORKER_ENABLED NOTIFICATION_WORKER_POLL_ENABLED
unset NOTIFICATION_WORKER_BATCH_SIZE NOTIFICATION_WORKER_POLL_DELAY
```

## Operator replay and migrations

Manual replay is an explicit operator-only, non-web CLI with audit
records, version checks, and command idempotency. There is no replay HTTP
endpoint. It requeues eligible failed work; it does not itself send email.

Follow [the operator replay and rollout runbook](docs/operator-replay.md).
Do not treat acknowledgement flags or an OS username as authentication or
proof that a previous sender has stopped.

Migrations:

- V1: notification and delivery-attempt tables
- V2: case-sensitive source/idempotency keys
- V3: retry counters, processing lease, conservative history backfill,
  recovery index, and UNKNOWN attempt outcome
- V4: immutable-through-application replay audit records
- V5: application-scoped email preferences and notification suppression

Stop old application writers/workers and operator commands before V3/V4
rollout. Use one controlled migrator and a verified backup/restore plan.
The backfill is not a zero-downtime rolling-upgrade procedure.

V5 also requires a stopped-writer rollout, not mixed P009/P010 binaries.
Keep workers disabled while only explicitly approved preferences are
created and due work is reviewed; do not automatically backfill opt-ins.
See the [preferences rollout requirements](docs/email-preferences.md#v5-rollout-and-verification-limits).
A populated V4-to-V5 rehearsal remains pending; historical V2-to-V4
evidence does not validate V5.

## API usage

These examples are for local dummy credentials/data only. Run them in a
terminal where `NOTIFICATION_EMPLOYEE_API_KEY` is set. The API accepts
plain-text notification content.

For a delivery demonstration, first create an enabled preference for the
exact example recipient using the same authenticated application. Follow
the [preferences API guide](docs/email-preferences.md).

Submission acceptance still confirms persistence, not permission to send
or completed delivery.

### Submit

```bash
curl -i 'http://localhost:8080/api/v1/notifications' \
  -H "X-API-Key: $NOTIFICATION_EMPLOYEE_API_KEY" \
  -H 'Content-Type: application/json' \
  --data '{
    "idempotencyKey": "employee-created-101",
    "recipientEmail": "recipient@example.invalid",
    "subject": "Welcome",
    "body": "Local demonstration only."
  }'
```

- `202 Accepted`: a new notification was committed to MySQL
- `200 OK`: an identical request returned its existing notification
- `409 Conflict`: the same business key had different content

The business key is `(sourceApplication, idempotencyKey)`. Source comes
from the authenticated API key, not the body. Comparisons are
case-sensitive. The same idempotency key in two source applications
represents two notifications. Conflicts do not overwrite original data.

Acceptance confirms durable submission, not email delivery.

### Retrieve status

Replace `NOTIFICATION_ID` with the submission result:

```bash
curl -i \
  'http://localhost:8080/api/v1/notifications/NOTIFICATION_ID' \
  -H "X-API-Key: $NOTIFICATION_EMPLOYEE_API_KEY"
```

The response masks the recipient and omits the body. A missing
notification or one owned by another application returns `404`.

Statuses: `PENDING`, `PROCESSING`, `RETRY_PENDING`, `SENT`, `FAILED`,
`SUPPRESSED`.

For suppressed work, `suppressionReason` is `PREFERENCE_MISSING` or
`EMAIL_DISABLED`; `failureCode` remains null. Suppression is terminal:
later opt-in does not resurrect it, an identical submission returns its
existing record, and operator replay cannot bypass it.

### Health and errors

```bash
curl 'http://localhost:8080/actuator/health'
```

Only health endpoints are exposed through Actuator. Health does not
prove provider availability or delivery.

API errors use `application/problem+json` with an error code and
correlation ID: authentication `401`; invalid input `400`; missing or
inaccessible notification/preference `404`; idempotency or preference
conflict `409`; unexpected failure `500`.

Preference duplicate creation returns `PREFERENCE_ALREADY_EXISTS`;
stale versions return `PREFERENCE_VERSION_CONFLICT`. A concurrent
database update may return `CONCURRENT_MODIFICATION`. Retrieve the
current preference and review before retrying.

Logs must not contain passwords, API keys, email bodies, or unmasked
request payloads. Worker logs use IDs/outcomes and error types. Raw
Hibernate JDBC error/warning logging is disabled because driver messages
can contain recipient-derived key values. Exceptions still propagate;
safe application-level error handling remains active. Do not enable
sensitive SQL bind tracing.

## Production readiness and contribution workflow

Remaining production work includes external SMTP-service and
mailbox-delivery validation, a strict whole-send deadline,
deployment-specific authorization, HTTPS, key rotation and secret
management, least-privilege separate accounts, metrics, alerts,
backup/restore verification, rollout controls, and representative load
and contention tests.

Do not present simulated or SMTP acceptance as confirmed mailbox delivery,
or the operator CLI as a complete production privileged-access system.

GitHub Actions is configured for pull requests to `main`, pushes to
`main`, and manual dispatch. It uses Java 17, Maven Wrapper, and its own
disposable MySQL 8.4 service with dummy credentials.

1. Update local `main` before starting new work.
2. Create a feature branch.
3. Implement and test the intended change.
4. Run `./mvnw clean verify` and `git diff --check`.
5. Review and stage the intended changes, including new files.
6. Commit and push the feature branch.
7. Open a pull request targeting `main`.
8. Review the full diff and passing CI before merging.

Do not switch branches mid-change, push application changes directly to
`main`, or commit generated output, credentials, or environment files.

Remaining capabilities include mailbox-delivery feedback, operational
hardening, separate worker deployment, and later Redis, Kafka and
Kubernetes work. These are not current implemented features.
