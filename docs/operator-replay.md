# P008 operator replay and rollout runbook

This runbook describes implemented behavior and required operator controls.
It is not authorization to replay any notification. The project is not yet
production-ready; use only approved local synthetic data until production
provider, access-control, deployment, and operating requirements are met.

## 1. Safety and trust boundary

- There is no replay HTTP endpoint. Replay is an opt-in, one-shot,
  non-web mode of the packaged application.
- A successful replay requeues work; it does not send email. Another
  enabled worker may send it later.
- Restrict the host, JAR, configuration, database credentials, and audit
  access. Use a separately approved operator account for the intended schema.
- The OS-derived Java `user.name` is audit attribution, not authentication.
  A person controlling the host/JVM can change it.
- Both CLI confirmation flags are operator attestations, not technical
  proof that a prior sender stopped or that duplicates are impossible.
- ORM immutability and the append-only application store do not prevent
  privileged direct SQL tampering. Retention/tamper-evidence controls are
  additional production work.
- Environment variables avoid putting passwords in application arguments,
  but are not a complete secret-management system. Privileged local users
  may inspect process environments.

Do not manually update status, version, counters, or attempt records as a
substitute for replay. Never delete attempt/audit history to bypass a gate.

## 2. Investigate before authorizing a replay

1. Identify the exact notification UUID and case-sensitive source application.
2. Confirm the intended database and authenticated account using a restricted
   read-only connection:
   ```sql
   SELECT DATABASE(), CURRENT_USER();
   ```
3. Review notification status/version, all attempt history, and any existing
   replay audit without exporting recipients, body, API keys, or credentials.
4. Investigate provider-side evidence when available. A timeout, process
   crash, failed outcome commit, or expired lease is not proof of non-delivery.
5. Stop/drain the previous sender. Ensure it cannot still complete an external
   request; lease expiry alone cannot fence or cancel it.
6. Stop other consumers of this queue during the controlled replay and
   verification. CLI-local worker settings do not stop another process.
7. Obtain explicit replay approval and duplicate-risk acknowledgement,
   recorded against a short investigation reference.
8. Choose a new globally unique command UUID for this new approved replay.
   Preserve the exact request and operator identity for safe retries.

For a NEW replay, the notification must be `FAILED`, its version must match,
and no attempt may have a NULL outcome **or** NULL completion time.
Fixing corrupt history is a separate investigated operation, not permission
to fabricate a completed attempt.

This does not make unknown deliveries exactly-once. If delivery remains
uncertain, the operator is explicitly accepting possible duplication.

## 3. Read-only investigation queries

Replace the UUID/source literals with the approved values and qualify
table names with the independently verified database where appropriate.

```sql
SELECT
    HEX(notification_id) AS notification_id,
    source_application, status, version,
    last_attempt_number, cycle_attempt_count, retry_count,
    next_attempt_at, last_attempt_at, processing_expires_at,
    failure_code, created_by_process, updated_by_process,
    created_at_time, updated_at_time
FROM notification
WHERE notification_id =
    UNHEX(REPLACE('REPLACE_WITH_CANONICAL_NOTIFICATION_UUID', '-', ''))
  AND source_application = 'REPLACE_WITH_EXACT_SOURCE_APPLICATION';
```

```sql
SELECT
    HEX(attempt_id) AS attempt_id, attempt_number,
    started_at_time, completed_at_time, outcome, failure_code,
    provider_message_id, created_by_process, updated_by_process,
    created_at_time, updated_at_time
FROM delivery_attempt
WHERE notification_id =
    UNHEX(REPLACE('REPLACE_WITH_CANONICAL_NOTIFICATION_UUID', '-', ''))
ORDER BY attempt_number;
```

```sql
SELECT
    HEX(command_id) AS command_id, source_application,
    expected_version, resulting_version,
    previous_failure_code, previous_cycle_attempt_count,
    last_attempt_number_at_replay, operator_id,
    investigation_reference, duplicate_risk_acknowledged,
    previous_sender_stopped, created_at_time, updated_at_time
FROM notification_replay
WHERE notification_id =
    UNHEX(REPLACE('REPLACE_WITH_CANONICAL_NOTIFICATION_UUID', '-', ''))
ORDER BY created_at_time, command_id;
```

Run these only in the verified schema. Provider IDs and investigation
metadata can themselves be sensitive; do not share raw results publicly.

## 4. CLI contract and startup gates

Exactly seven replay options are required, each in `--name=value` form:

| Option | Requirement |
| --- | --- |
| `replay.command-id` | Canonical hyphenated UUID; globally unique for a new command |
| `replay.notification-id` | Canonical hyphenated UUID |
| `replay.source-application` | Exact owner; nonblank, at most 100 characters, no control characters |
| `replay.expected-version` | Nonnegative decimal, no leading zeroes except 0; less than Long.MAX_VALUE |
| `replay.investigation-reference` | Pattern shown below |
| `replay.duplicate-risk-acknowledged` | Literal lowercase `true` |
| `replay.previous-sender-stopped` | Literal lowercase `true` |

Reference pattern: `[A-Za-z0-9][A-Za-z0-9._:/#-]{0,99}`.
Use a short ticket/reference, not arbitrary prose or an arbitrary URL.

The only additional accepted argument is
`--spring.profiles.active=operator-replay`. Do not add datasource,
config-location, port, operator-ID, or other configuration arguments.
Unknown/duplicate/positional/empty options and `--name value` syntax
are rejected. UUID letter case is accepted; source/reference values are
not trimmed or case-normalized.

`operator-replay` must be the only active profile: do not combine it with
`local`, `test`, or `prod`. The startup guard requires:

| Effective setting | Required value |
| --- | --- |
| `spring.main.web-application-type` | `none` |
| `notification.worker.enabled` | `false` |
| `notification.worker.poll-enabled` | `false` |
| `spring.flyway.enabled` | `false` |
| `spring.sql.init.mode` | `never` |
| `spring.jpa.generate-ddl` | `false` |
| `spring.jpa.hibernate.ddl-auto` | `validate` |

Actual web application contexts are rejected. Native JPA overrides are
rejected except `hibernate.jdbc.time_zone=UTC`. Argument validation runs
before ordinary application beans. API authentication is servlet-only;
this non-web mode does not require an API key.

The operator guard does NOT validate the target URL/schema/account grants.
Use reviewed configuration and restricted credentials. External
`spring.datasource.hikari.*` settings can otherwise select a different
target even when generic datasource properties look correct.

## 5. Controlled launch template — not an instruction to run now

Use an approved, newly packaged JAR whose database has already been
migrated to the compatible release. Replay mode never runs migrations.

Before entering credentials, confirm the approved JAR exists at the launch
path below. If it is missing, stop and locate/build the reviewed artifact;
do not substitute a different release merely to make the command run.

Before using the template, prepare these values in an approved private
shell. Do not type a password into an assignment/command argument or
enable shell tracing.

- `REPLAY_DB_URL`: approved JDBC URL without embedded credentials
- `REPLAY_DB_USERNAME`: approved restricted operator account
- `REPLAY_DB_PASSWORD`: read interactively as shown below
- `REPLAY_COMMAND_ID`, `REPLAY_NOTIFICATION_ID`
- `REPLAY_SOURCE_APPLICATION`, `REPLAY_EXPECTED_VERSION`
- `REPLAY_INVESTIGATION_REFERENCE`

Read the password as separate commands:

```bash
set +x
IFS= read -r -s -p "Approved operator database password: " REPLAY_DB_PASSWORD
printf '\n'
```

The following is a Bash template. If a terminal cannot accept a large
paste, save it in a reviewed local script outside the repository and
export the required input variables for that child script. Never use
production credentials in checked-in scripts.

Both confirmations below may be supplied only after section 2 is
actually satisfied. They are not defaults to accept without investigation.

```bash
(
  set +x
  set -e
  set -u

  p008_db_url="$REPLAY_DB_URL"
  p008_db_username="$REPLAY_DB_USERNAME"
  p008_db_password="$REPLAY_DB_PASSWORD"

  # Clear inherited configuration; enumerate names, never values.
  while read -r p008_env_key; do
    case "$p008_env_key" in
      SPRING_*|NOTIFICATION_*|DB_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)
        unset "$p008_env_key"
        ;;
    esac
  done < <(compgen -e)

  unset REPLAY_DB_URL REPLAY_DB_USERNAME REPLAY_DB_PASSWORD

  # Restrict config loading to the reviewed packaged base/profile files.
  export SPRING_CONFIG_LOCATION='classpath:/application.yaml'
  export DB_URL="$p008_db_url"
  export DB_USERNAME="$p008_db_username"
  export DB_PASSWORD="$p008_db_password"

  export SPRING_DATASOURCE_URL="$DB_URL"
  export SPRING_DATASOURCE_USERNAME="$DB_USERNAME"
  export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
  export SPRING_MAIN_WEB_APPLICATION_TYPE='none'
  export SPRING_FLYWAY_ENABLED='false'
  export SPRING_SQL_INIT_MODE='never'
  export SPRING_JPA_GENERATE_DDL='false'
  export SPRING_JPA_HIBERNATE_DDL_AUTO='validate'
  export NOTIFICATION_WORKER_ENABLED='false'
  export NOTIFICATION_WORKER_POLL_ENABLED='false'
  unset p008_db_url p008_db_username p008_db_password

  if java -jar "target/scalable-notification-platform-0.0.1-SNAPSHOT.jar" \
    --spring.profiles.active=operator-replay \
    --replay.command-id="$REPLAY_COMMAND_ID" \
    --replay.notification-id="$REPLAY_NOTIFICATION_ID" \
    --replay.source-application="$REPLAY_SOURCE_APPLICATION" \
    --replay.expected-version="$REPLAY_EXPECTED_VERSION" \
    --replay.investigation-reference="$REPLAY_INVESTIGATION_REFERENCE" \
    --replay.duplicate-risk-acknowledged=true \
    --replay.previous-sender-stopped=true; then
    p008_status=0
  else
    p008_status=$?
  fi

  printf 'REPLAY_PROCESS_EXIT=%s\n' "$p008_status"
  exit "$p008_status"
)
```

Start from the repository root, or adjust only the JAR path to the
approved artifact. The known packaged base/operator config contains no
external config imports; review that assumption if the release changes.

The subprocess isolates these changes from its parent shell. If the
password variable was set in the parent, explicitly unset it afterward,
including after a failed or interrupted launch; a child cannot remove it
from the parent:

```bash
unset REPLAY_DB_PASSWORD
```

Spring Boot expands an explicit file location to its profile variant and
replaces the default search locations. This is why the environment setting
excludes unintended current-directory/config files while retaining
`application-operator-replay.yaml`. See [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).

## 6. Interpret and verify the result

Successful execution logs:

```text
Manual replay recorded: commandId=..., notificationId=..., requeuedVersion=...
```

The process runs once, closes its application context, and exits normally.
This log means requeued, not delivered. The CLI defines no stable numeric
business-error exit-code mapping; startup/service failures normally exit
nonzero. Do not interpret an exit code alone as proof of commit/rollback.

For a NEW successful replay, verify in a separate read-only session:

- Notification is `PENDING` and due now, with its incremented version.
- Current cycle count is 0; failure code, provider ID, and lease are cleared.
- Payload, business key/hash, lifetime attempt sequence, cumulative retry
  count, last-attempt timestamp, creation audit, and all old attempts are
  preserved.
- Update process is `notification-replay-cli`.
- Exactly one audit exists for this command, matching the request,
  operator, investigation reference, confirmations, prior failure/cycle
  metadata, expected/resulting versions, and replay timestamp.

Requeue and audit insertion commit in one transaction. Do not restart a
consumer until the result and intended queue scope have been reviewed.

### Identical command versus conflicting command

Command UUIDs are globally unique. Repeating the exact same request,
including operator identity/reference/flags, returns the original stored
result without a second requeue or audit. That result is a snapshot, even
if the notification has subsequently become `SENT`. Inspect current state
separately; do not require PENDING after an identical repeat.

Reusing a recorded command with different details is rejected.
Cross-notification simultaneous collisions can surface as a database
uniqueness/data-integrity error rather than the friendly conflict reason;
the losing transaction rolls back its requeue and audit.

### Rejection reasons

| Reason | Meaning/action |
| --- | --- |
| `NOT_FOUND` | Missing notification or wrong source; investigate identity/ownership |
| `COMMAND_CONFLICT` | Recorded command ID has different details; do not reuse it for new work |
| `NOT_FAILED` | New replay requires FAILED; do not force another state |
| `STALE_VERSION` | State changed; investigate and obtain a newly reviewed decision |
| `OPEN_ATTEMPTS` | Incomplete/partially closed history exists; investigate before any correction |

Invalid input is reported without echoing supplied argument values.
Unsafe configuration is refused rather than silently changing it.

### Ambiguous exit, timeout, or connection loss

Stop and inspect both notification and audit. A lost client response or
interrupted process does not prove that the transaction failed.

After resolving uncertainty and retaining the same authorized actor,
retry only the exact original command/request. Do not generate a new
command UUID merely because output was lost; that could authorize a
second replay. Never automate retries of a different command.

## 7. P008 schema rollout

This is a controlled stopped-writer rollout, not a zero-downtime rolling
upgrade:

1. Verify the release, tests, approved maintenance window, and backup/restore
   procedure on representative data.
2. Stop/drain old API writers, all workers, and operator commands. Pause
   incoming submissions during the schema-change window.
3. Run one controlled Flyway migrator against the intended schema. Current
   normal local/prod application startup runs Flyway; operator mode does not.
   If using startup as the migrator, keep workers disabled and all older
   instances stopped until migration/validation is complete.
4. V3 adds counters/lease/recovery support. Backfill uses persisted history
   MAX(attempt_number) and COUNT(*), defaulting to zero without history.
   Legacy leases remain NULL; no historical attempts/audits are invented.
5. V4 creates replay audit storage with a globally unique command ID and
   notification foreign key.
6. Validate schema history/checksums and compare expected data/counters.
   Start only compatible code; restore traffic/worker polling deliberately.
7. Do not restart P007 writers or assume application rollback is compatible
   with new counters/outcomes. Any rollback needs a separately verified plan.

MySQL DDL can commit independently; the whole migration cannot be assumed
to roll back on failure. Preserve failed state and investigate. Do not
blindly edit applied migrations, clean/repair schema history, or rerun
DDL to hide a partial change. See [Flyway transaction handling](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-transaction-handling).

## 8. Verification record and outstanding limits

On 17 October 2026, local P008 verification reported:

- 176 standard tests passed with zero failures/errors/skips.
- Packaged non-web CLI passed first replay, identical repeat, and
  conflicting-command checks.
- Synthetic smoke cleanup committed; normal test data returned to 0/0/0.
- A separate [populated V2-to-V4 rehearsal](upgrade-rehearsal.md) passed:
  four legacy notifications/four attempts preserved; correct counters,
  empty replay audit table, unchanged migration history on no-op repeat.

These checks do not prove all historical datasets, production rollback,
tamper-proof audit retention, provider delivery, or multi-worker throughput.
The current provider remains a simulator. PR review and CI remain separate
release gates.

The separate upgrade database/account are intentionally retained for
inspection. Removing them requires a separately approved exact-target
cleanup; no destructive cleanup commands are part of this runbook.
