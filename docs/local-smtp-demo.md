# Local SMTP capture demonstration

This demonstrates API submission, durable worker processing, SMTP acceptance
and idempotent repetition. Mailpit captures messages locally; this does not
prove external inbox delivery or exactly-once delivery.

## Prerequisites

- JDK 17, the repository's Maven Wrapper, and Docker Engine/Compose available.
- Follow the [database setup](../README.md#local-database-setup), including
  the native-versus-container account distinction.
- Use the development database `notification_platform`, never the test or
  production database. This guide does not provision MySQL accounts.
- The development account needs the reviewed V5 `email_preference` table
  privileges, using its actual native `@'localhost'` or Docker `@'%'`
  identity. Review existing grants; do not recreate accounts or broaden
  privileges simply to make the demo pass.
- Keep Kubernetes disabled; no external SMTP account is required.
- Use only synthetic data. Stop other application/worker instances.
- Use dedicated, reviewed terminals without unrelated Spring/JVM overrides.
- Keep credentials outside Git and never include them in screenshots or logs.

## Start Mailpit

Create `compose.mailpit.yaml` outside the repository. The verified Windows
location is `D:\DevData\NotificationPlatform`.

```yaml
services:
  mailpit:
    image: axllent/mailpit:v1.32.0
    restart: "no"
    mem_limit: 256m
    ports:
      - "127.0.0.1:1025:1025"
      - "127.0.0.1:8025:8025"
    environment:
      MP_DATABASE: /data/mailpit.db
      MP_ALLOWED_HOSTS: "localhost,127.0.0.1"
      MP_DISABLE_VERSION_CHECK: "true"
      MP_SMTP_DISABLE_RDNS: "true"
      MP_SMTP_ALLOWED_RECIPIENTS: '^recipient@example[.]invalid$$'
      MP_SMTP_RELAY_ALL: "false"
      MP_QUIET: "true"
    volumes:
      - mailpit-data:/data
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"

volumes:
  mailpit-data:
```

Compose converts `$$` to the literal `$` regex anchor. Relay and forwarding
are not configured. Docker's data-disk location determines where named-volume
data is stored; the verified Windows setup places that disk on D:.

Choose **one** startup variant, from the directory containing the YAML.

### Existing Docker MySQL stack

Preserve the existing `compose.yaml` and its project name. Start only Mailpit:

```text
docker compose -f compose.yaml -f compose.mailpit.yaml config --quiet
docker compose -f compose.yaml -f compose.mailpit.yaml up -d --no-deps --wait --wait-timeout 120 mailpit
```

### Native MySQL

Start only the Mailpit file under a separate explicit project:

```text
docker compose -p notification-platform-smtp-demo -f compose.mailpit.yaml config --quiet
docker compose -p notification-platform-smtp-demo -f compose.mailpit.yaml up -d --wait --wait-timeout 120 mailpit
```

Do not start both variants. If Mailpit is already running, reuse the
reviewed existing setup rather than creating a second instance.

Open [Mailpit](http://127.0.0.1:8025) after it becomes healthy.
Do not recreate MySQL or delete any volume.

## Review development data before enabling the worker

Using the approved database-administrator connection, inspect:

```sql
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'notification_platform';
```

A fresh schema may have no application tables; Flyway initializes it on
application startup. If tables already exist, review their ownership and
migration state. For an existing notification table, inspect only counts:

```sql
SELECT status, COUNT(*) AS record_count
FROM notification_platform.notification
GROUP BY status;
```

Do not enable this demo worker while older `PENDING`, `RETRY_PENDING` or
`PROCESSING` work exists. Empty tables are acceptable. Previously captured,
owned synthetic terminal records may remain after review. Pause on unexpected
data or partial migrations; do not clear rows to make the demo pass.

## Start the application: Git Bash terminal A

From the repository root, run credential-prompt lines individually and wait
for each hidden prompt. Use a unique private 32–64-character alphanumeric
local API key; terminal B needs the same value.

```bash
set +x
export DB_URL='jdbc:mysql://127.0.0.1:3306/notification_platform?serverTimezone=UTC'
export DB_USERNAME='notification_user'
IFS= read -r -s -p "Development database password: " DB_PASSWORD
printf '\n'
export DB_PASSWORD
IFS= read -r -s -p "Temporary local API key: " NOTIFICATION_EMPLOYEE_API_KEY
printf '\n'
export NOTIFICATION_EMPLOYEE_API_KEY
```

```bash
P009_RUN_ARGS=(
  '--server.address=127.0.0.1'
  '--notification.email.provider=smtp'
  '--notification.email.smtp.host=127.0.0.1'
  '--notification.email.smtp.port=1025'
  '--notification.email.smtp.from-address=notifications@example.invalid'
  '--notification.email.smtp.security=PLAIN'
  '--notification.email.smtp.authentication=false'
  '--notification.worker.enabled=true'
  '--notification.worker.poll-enabled=true'
  '--notification.worker.batch-size=1'
  '--notification.worker.poll-delay=PT5S'
)
./mvnw spring-boot:run "-Dspring-boot.run.profiles=local" \
  "-Dspring-boot.run.arguments=${P009_RUN_ARGS[*]}"
```

Wait for application startup. Plaintext is permitted only for eligible
local/test profiles and literal loopback SMTP hosts. It is not a production
SMTP configuration.

## Submit and inspect: Git Bash terminal B

```bash
curl -q --noproxy '*' -sS --connect-timeout 5 --max-time 20 \
  http://127.0.0.1:8080/actuator/health
```

Require `"status":"UP"`. Enter the same API key, one prompt line at a time:

```bash
set +x
IFS= read -r -s -p "Same temporary local API key: " P009_DEMO_API_KEY
printf '\n'
```

The helper passes the key through standard input, not curl's argument list:

```bash
p009_api() {
  local p009_path="$1"
  shift
  printf 'X-API-Key: %s\n' "$P009_DEMO_API_KEY" |
    curl -q --noproxy '*' --silent --show-error --include \
      --connect-timeout 5 --max-time 20 --header @- "$@" \
      "http://127.0.0.1:8080${p009_path}" --write-out '\n'
}
```

### Create or inspect the required preference

Before submitting a notification, create an explicit opt-in:

```bash
p009_api /api/v1/email-preferences \
  --header 'Content-Type: application/json' \
  --data '{
    "recipientEmail": "recipient@example.invalid",
    "settings": {
      "emailEnabled": true,
      "localeTag": "en-IN",
      "quietHours": null
    }
  }'
```

Require HTTP 201. If the preference already exists and returns 409,
inspect it without automatically overwriting settings:

```bash
p009_api /api/v1/email-preferences/lookup \
  --header 'Content-Type: application/json' \
  --data '{"recipientEmail":"recipient@example.invalid"}'
```

Proceed only after confirming the expected application, exact recipient,
emailEnabled=true and absent/null quiet hours. Otherwise stop for a
reviewed, version-checked preference update.

See the [preferences guide](email-preferences.md) for ownership, version
checks and claim-time behavior.

Now generate the demo idempotency key and submit the notification.

```bash
P009_DEMO_KEY="p009-mailpit-$(date -u +%s)-$RANDOM"
P009_DEMO_JSON=$(printf '{"idempotencyKey":"%s","recipientEmail":"recipient@example.invalid","subject":"P009 SMTP demo %s","body":"Local SMTP capture only."}' "$P009_DEMO_KEY" "$P009_DEMO_KEY")
printf 'Demo idempotency key: %s\n' "$P009_DEMO_KEY"
p009_api /api/v1/notifications --header 'Content-Type: application/json' \
  --data "$P009_DEMO_JSON"
```

For a new request, expect HTTP `202`. Copy its notification ID:

```bash
P009_NOTIFICATION_ID='PASTE_NOTIFICATION_ID_HERE'
p009_api "/api/v1/notifications/$P009_NOTIFICATION_ID"
```

After processing, require `SENT`, `retryCount: 0` and `failureCode: null`.
A short `PENDING`/`PROCESSING` interval is normal; check again shortly.
For `FAILED` or `RETRY_PENDING`, stop and investigate the safe failure code.
Do not create replacement keys to bypass an uncertain delivery outcome.

For `SUPPRESSED`, inspect `suppressionReason` and stop this run. Later
opt-in does not resurrect that notification, and an identical submission
returns the same terminal record. Do not manually change its status or
use operator replay to bypass suppression.

Find exactly one message with this request's unique subject in Mailpit.
Repeat the identical submission:

```bash
p009_api /api/v1/notifications --header 'Content-Type: application/json' \
  --data "$P009_DEMO_JSON"
```

Require HTTP `200`, the same notification ID and no additional matching
capture. Scope the count to this subject, not the entire Mailpit inbox.

## Stop safely

Press Ctrl+C in terminal A and wait for shutdown, then clear its credentials:

```bash
unset DB_PASSWORD NOTIFICATION_EMPLOYEE_API_KEY
```

In terminal B:

```bash
unset P009_DEMO_API_KEY
```

Leave MySQL running for the standard test suite. Do not delete records,
messages or volumes; `docker compose down -v` would remove persistent data.

## Local evidence — 25 October 2026

This is historical P009 evidence. The combined P010 preference-to-live-SMTP
demonstration has not yet been rerun; the updated procedure above does not
itself establish a new successful capture.

- Full `clean verify`: 248 tests, zero failures/errors/skips; JAR packaged.
- User-reported demo: new POST `202` → persisted `SENT` → one Mailpit capture;
  identical repeat `200` with the same notification ID.
- PR review and CI are separate verification gates. The standard automated
  SMTP tests use mocks/disconnected senders, not a live Mailpit service.
