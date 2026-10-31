# P008 populated-schema upgrade rehearsal

A deliberately opt-in, one-shot check of historical V2 data surviving
the original V3/V4 migrations. The original verification used native local
MySQL; Docker is not required for the documented native procedure, and no
additional paid tooling is needed.

This historical rehearsal targets V2 to V4 only. It does not apply or
validate P010's V5 email-preference migration, and it does not provision
the current Docker environment.

## Scope and evidence

On 17 October 2026 the local rehearsal passed:

```text
UPGRADE_REHEARSAL_OK schema=notification_platform_upgrade_test oldVersion=2 newVersion=4 notifications=4 attempts=4 replayAudits=0 repeatMigrations=0
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The rehearsal database/account were retained at the 17 October run. Do not
assume they still exist after an operating-system reinstall or a move to
Docker; verify the exact environment and account identity before using
this guide. Do not rerun setup or this test against an already populated
rehearsal database.

Test class:

```text
src/test/java/io/github/amitvishwa/notification/infrastructure/persistence/migration/NotificationMigrationUpgradeRehearsal.java
```

The class is plain JUnit with JDBC/Flyway, not a Spring Boot application
test. It starts no application context, HTTP server, worker, provider, or
operator CLI.

It deliberately does not match the standard Surefire test-name patterns,
so ordinary builds/CI do not require its additional local database.
Explicit execution also requires `NOTIFICATION_UPGRADE_REHEARSAL=true`.

## What the test verifies

1. Connect to the hardcoded local upgrade database/account and reject
   pre-existing visible tables/views.
2. Apply original classpath V1/V2 migrations, target version 2.
3. Insert four synthetic notifications/four attempts transactionally,
   using only original V1/V2 columns.
4. Snapshot every original notification and attempt field, including
   payload/hash, status, counters, version, timestamps, and process audits.
5. Apply original V3/V4, target version 4; validate checksums.
6. Compare the snapshots, assert correct backfilled counters, NULL legacy
   leases, empty replay audit table, and successful history versions 1–4.
7. Repeat migrate, expect zero executed migrations and unchanged data/history.

| Legacy fixture | Existing history | Expected lifetime sequence / cycle count |
| --- | --- | --- |
| PENDING | No attempts | 0 / 0 |
| RETRY_PENDING | Closed attempts 1 and 3 | 3 / 2 |
| PROCESSING | Open attempt 1 | 1 / 1 |
| SENT | Closed ACCEPTED attempt 1 | 1 / 1 |

The sparse-history fixture deliberately uses `retry_count=7` as a synthetic
sentinel. That value must remain unchanged. It proves new counters derive
from persisted MAX/COUNT history rather than `retry_count + 1`. It is not
a normal lifecycle example.

Binary UUIDs are compared through HEX strings; timestamps are compared
as microsecond-formatted SQL values. This avoids Java byte-array identity
and driver timestamp-representation noise.

This proves the specified synthetic upgrade, not compatibility with every
real historical dataset, production rollback, or a live rolling upgrade.

## Preconditions and stop rules

- Use only `notification_platform_upgrade_test` and
  `notification_upgrade_test@localhost`.
- The database must be genuinely new, not merely an old schema with
  empty application tables.
- Run only one rehearsal and keep all other processes away from it.
- The administrator setup below must confirm freshness. Restricted users
  may not see unrelated objects in information_schema, so the test's
  empty-schema assertion alone is not sufficient.
- Never edit V1–V4, manually create application tables, or use clean,
  baseline, repair, undo, or an automatic reset.
- If setup or migration fails, stop and preserve the state. Do not rerun
  creation or broaden grants to make an unknown error disappear.
- MySQL DDL can commit independently; a failed migration need not have
  rolled back in full.
- The test has no automatic cleanup, repair, or retry. Any later removal
  of this database/account needs a separately approved exact-target action.

## A. Fresh administrator setup

Use a local MySQL administrator connection for setup only, not for running
the Java rehearsal. Execute each block separately, stopping on errors.

### A1. Check both exact names are unused

```sql
SELECT SCHEMA_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'notification_platform_upgrade_test';

SELECT User, Host
FROM mysql.user
WHERE User = 'notification_upgrade_test';
```

Both results must contain zero rows. Otherwise stop: do not reuse,
overwrite, drop, or modify an existing database/account.

### A2. Create the database, then the restricted account

```sql
CREATE DATABASE notification_platform_upgrade_test
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
```

```sql
CREATE USER 'notification_upgrade_test'@'localhost'
    IDENTIFIED BY RANDOM PASSWORD;
```

The second statement returns a generated password. Store it privately and
never share the result, screenshots, or the password in Git/chat. Do not
rerun CREATE USER if you missed the password; investigate separately.

There is deliberately no IF NOT EXISTS.

### A3. Grant access to exactly the four migration tables

```sql
GRANT ALL PRIVILEGES
ON `notification_platform_upgrade_test`.`notification`
TO 'notification_upgrade_test'@'localhost';

GRANT ALL PRIVILEGES
ON `notification_platform_upgrade_test`.`delivery_attempt`
TO 'notification_upgrade_test'@'localhost';

GRANT ALL PRIVILEGES
ON `notification_platform_upgrade_test`.`notification_replay`
TO 'notification_upgrade_test'@'localhost';

GRANT ALL PRIVILEGES
ON `notification_platform_upgrade_test`.`flyway_schema_history`
TO 'notification_upgrade_test'@'localhost';
```

CREATE is included, so MySQL permits these exact-table grants before the
tables exist. No schema/global wildcard grant, WITH GRANT OPTION, or
FLUSH PRIVILEGES is needed. These are rehearsal migration privileges,
not production application runtime privileges.

### A4. Confirm grants and emptiness as administrator

```sql
SHOW GRANTS FOR 'notification_upgrade_test'@'localhost';

SELECT COUNT(*) AS objects_before
FROM information_schema.tables
WHERE table_schema = 'notification_platform_upgrade_test';
```

Expect USAGE plus the four exact-table grants, no unexpected roles/grants,
and `objects_before=0`. Flyway will create the tables.

## B. Run only the explicit rehearsal

From the repository root in Git Bash, verify the rehearsal source exists.
No POM or application YAML changes are needed.

Run the following commands one at a time:

```bash
set +x
```

```bash
IFS= read -r -s -p "Upgrade account password: " NOTIFICATION_UPGRADE_DB_PASSWORD
```

Enter the new account's password at the prompt and press Enter; nothing
is displayed. This is not the normal `notification_test` password.

```bash
printf '\n'
```

```bash
export NOTIFICATION_UPGRADE_DB_PASSWORD
```

```bash
export NOTIFICATION_UPGRADE_REHEARSAL=true
```

```bash
./mvnw "-Dtest=NotificationMigrationUpgradeRehearsal" test
```

The URL/schema/account are hardcoded in the test. Normal `DB_URL` and
Spring datasource settings do not select its connection target. Only the
dedicated password comes from its environment variable.

Flyway uses the original `classpath:db/migration` location with clean
disabled, schema creation disabled, baseline-on-migrate disabled, and
validation enabled. Do not change migrations to accommodate a fixture.

After Maven returns, even on failure, remove the two rehearsal variables:

```bash
unset NOTIFICATION_UPGRADE_REHEARSAL NOTIFICATION_UPGRADE_DB_PASSWORD
```

This removes terminal variables only; it does not remove database objects.

## C. Interpret results and preserve evidence

Require one test, zero failures/errors/skips, BUILD SUCCESS, and the exact
success marker shown at the top. A skipped test is not a completed rehearsal.

On success, four notifications/four attempts remain, the replay table is
empty, and Flyway records successful versions 1–4. Repeated migration was
already checked inside the test; do not rerun the test on this populated
schema.

On failure, preserve the database and share only the relevant sanitized
exception. Never assume a transaction rolled back the schema DDL.

Do not clear `notification_platform_test` to clean up this separate
rehearsal. The normal development/test databases are not used here.

## References

- [Flyway target](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-target-setting),
  [clean-disabled](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-clean-disabled-setting),
  and [create-schemas](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-create-schemas-setting)
- [MySQL CREATE USER](https://dev.mysql.com/doc/refman/9.7/en/create-user.html)
  and [GRANT](https://dev.mysql.com/doc/refman/9.7/en/grant.html)
- [MySQL implicit commits](https://dev.mysql.com/doc/refman/9.7/en/implicit-commit.html)
- [Surefire default test patterns](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html)
  and [explicit single-class execution](https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html)
