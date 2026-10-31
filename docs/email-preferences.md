# P010 — Email preferences

The platform supports application-scoped email preferences, explicit
opt-in, daily quiet hours and optimistic concurrency.

The application remains under development and is not production-ready.

## Identity and ownership

A preference is identified by the exact pair:

(sourceApplication, recipientEmail)

The source application comes from the authenticated API key, never the
request body. Recipient spelling is preserved without lowercasing,
trimming or alias normalization.

The server assigns preferenceId. The ID, source application and recipient
cannot be changed through the update API.

Settings are mutable. Responses include the persisted version and
creation/latest-update audit fields. These fields are not a complete
append-only change history or proof of end-user consent.

## API contract

All endpoints require X-API-Key and return Cache-Control: no-store.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | /api/v1/email-preferences | Create; 201 or duplicate-key 409 |
| POST | /api/v1/email-preferences/lookup | Lookup using recipient in the body; 200 or 404 |
| GET | /api/v1/email-preferences/{preferenceId} | Owner-scoped lookup; 200 or 404 |
| PUT | /api/v1/email-preferences/{preferenceId} | Replace settings using expectedVersion; 200 or 409 |

Recipient addresses are not placed in URLs. Unknown or inaccessible
preferences return the same not-found response.

Repeating preference creation returns 409 even when settings are
identical. It never overwrites the existing preference. This differs
from notification submission, where an identical idempotent repeat
returns 200.

### Create

```json
{
  "recipientEmail": "recipient@example.invalid",
  "settings": {
    "emailEnabled": true,
    "localeTag": "en-IN",
    "quietHours": {
      "start": "22:00",
      "end": "07:00",
      "timeZone": "Asia/Kolkata"
    }
  }
}
```

emailEnabled must be explicitly supplied as true or false.

localeTag is required and stored as a canonicalized, well-formed BCP47
language tag. It is a stored hint, not translation, a supported-language
registry or a change to the notification subject/body.

quietHours may be omitted or null.

### Lookup

POST /api/v1/email-preferences/lookup:

```json
{
  "recipientEmail": "recipient@example.invalid"
}
```

### Update

Read the current preference first and use its returned version:

```json
{
  "expectedVersion": 0,
  "settings": {
    "emailEnabled": false,
    "localeTag": "en-IN",
    "quietHours": null
  }
}
```

The example version is not a value to reuse blindly.

Updates require the complete settings object. Omitting quietHours or
setting it to null removes quiet hours. Source, recipient and ID fields
are not accepted in the update body.

A stale expected version returns PREFERENCE_VERSION_CONFLICT. A
concurrent database update may return CONCURRENT_MODIFICATION. Both are
409 responses. Retrieve the current resource and review before retrying.

Always use the returned persisted version; do not assume every
same-settings PUT must increment it.

## Quiet-hours rules

- A daily recurring local-time window, including overnight windows.
- Start is inclusive; end is exclusive.
- Start and end must differ and use exact HH:mm precision.
- A named JDK timezone is required, such as Asia/Kolkata or UTC.
- Numeric offset IDs such as +02:00 are rejected.
- Timezone rules, including DST transitions, determine eligibility.

The stored nextAttemptAt is earliest eligibility, not a delivery deadline.

## Worker behavior

Retry exhaustion is checked before preferences. Exhausted work remains
FAILED / RETRY_EXHAUSTED without another attempt.

For remaining eligible work:

| Preference decision | Notification behavior |
| --- | --- |
| Missing | Terminal SUPPRESSED / PREFERENCE_MISSING |
| Disabled | Terminal SUPPRESSED / EMAIL_DISABLED |
| Inside quiet hours | Persist a future nextAttemptAt; retain pending status |
| Enabled and eligible | Create a processing claim and delivery attempt |

Suppression and quiet-hours deferral do not create a new delivery attempt
or consume additional retry budget. Existing attempt history is retained.

Preferences are evaluated using the worker batch's claim timestamp.
Changes affect subsequent eligible claims, not already-claimed work.
There is no recall or cancellation guarantee for a claim or provider
call already in progress.

Changing preferences does not scan or reschedule notifications or pull
forward an existing nextAttemptAt. Previously deferred work remains
scheduled until its stored due time, when preferences are reevaluated.

Missing, corrupt or unreadable settings never authorize delivery.
Unexpected evaluation failures abort the claim transaction.

## Suppression and notification status

Notification status includes:

PENDING, PROCESSING, RETRY_PENDING, SENT, FAILED, SUPPRESSED

SUPPRESSED is a preference decision, not a provider failure. The status
API returns suppressionReason separately from failureCode.

A suppressed notification is terminal. Later opt-in does not resurrect
it. Repeating its notification idempotency key returns the existing
record. SUPPRESSED is not replay-eligible.

An approved replay of FAILED work does not bypass preferences: its next
eligible claim evaluates them again.

## Privacy

The authorized preference API returns the exact recipient. Do not publish
raw preference responses. Notification status continues to mask the
recipient and omit message content.

Hibernate raw JDBC error/warning logging is disabled because database
messages can contain recipient-derived key values. Application-level
safe error handling remains active. Do not enable sensitive SQL bind
tracing or log raw request bodies, keys or credentials.

## V5 rollout and verification limits

V5 creates email_preference and adds notification suppression support.
Existing V1–V4 migrations remain unchanged.

This is a stopped-writer rollout, not a mixed-version rolling upgrade:

1. Stop/drain old API instances, workers and replay commands.
2. Verify backups, restore procedures and representative migration behavior.
3. Apply V5 using one controlled migrator.
4. Start only P010-compatible code with workers disabled.
5. Create only explicitly approved preferences and review pending work.
6. Enable processing deliberately.

Existing eligible queued work without preferences will be suppressed.
There is no automatic opt-in backfill.

Do not restart P009 binaries or assume application rollback is compatible:
older code cannot interpret SUPPRESSED and older workers do not enforce
preferences.

MySQL DDL can commit independently. Preserve and investigate partial
migration state; do not blindly clean, repair or edit migration history.

Current tests cover V5 installation, constraints, API ownership and
concurrency, worker behavior, status responses and logging privacy.
A populated V4-to-V5 upgrade rehearsal remains pending. The historical
V2-to-V4 rehearsal does not cover V5.

The combined P010 preference-to-live-SMTP demonstration has not yet been
rerun. Historical P009 Mailpit evidence remains separate.

Redis caching, multi-channel preferences, translation, a preference UI
and complete preference-change history are outside this milestone.