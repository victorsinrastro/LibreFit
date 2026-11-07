# First-party training data system

LibreFit's Room database is the source of truth for active workout state. Database version 9 added
the persisted active-session clock. Version 10 adds an append-only revision ledger, training blocks
and plans, typed custom measurements, explicit load meaning, and nullable set-action timestamps.
Health Connect is optional external context; its schema does not determine GZCLP prescriptions,
sets, or progression.

## Data model

| Table | Meaning |
|---|---|
| `gzclp_program`, `gzclp_template_exercises`, `gzclp_progress` | Current configuration, rotation, and separate per-tier progression |
| `training_blocks`, `training_plans` | Stable block and next-session snapshots; actual session may differ after a later edit |
| `gzclp_sessions`, `gzclp_session_exercises`, `gzclp_sets` | Actual sessions, prescribed exercises, prescribed and performed sets |
| `training_events` | Ordered before/after revisions for meaningful writes and progression decisions |
| `training_measurement_definitions`, `training_measurements` | Typed, bounded optional measurements with provenance and quality |

All new block, plan, event, definition, and measurement IDs are UUIDs. Existing GZCLP numeric
session/set IDs remain stable. Weights are integer grams. `loadMeaning` distinguishes total
barbell load, per dumbbell, machine display, cable stack, and bodyweight; null means unknown.
`targetGrams`/`targetReps` preserve the prescription; `actualGrams`/`actualReps` preserve what was
entered. `completedAtMillis` records the actual completion tap time and remains null for legacy
sets. `lastEditedAtMillis` records an edit, not a guessed performance time. New sessions store
active elapsed time and the start of the current running interval; paused intervals are excluded.

The ledger is written in the same transaction as the current row. A repeated no-op write emits
no second revision. Each revision records a kind, subject, session join key, observation time,
recording time, before/after JSON, reason, provenance, and time quality. Normal workout logging
still requires one tap; revision capture is automatic. Notes, RIR, custom measurements, and
pre/post check-ins remain optional. Settings → Data can delete revision history explicitly while
keeping current workouts. Deleting a custom observation emits a deletion revision until the user
clears the ledger.
The general-purpose LibreFit workout editor also records aggregate workout/exercise/set snapshots
when it saves, edits, or deletes a workout. Its legacy local date-time fields retain their original
precision and unknown time zone; the export does not manufacture UTC set times for them.

The GZCLP engine remains authoritative. Revision rows explain what changed, while the normalized
tables hold the current state. Replaying a historical correction continues to use the existing
conflict-aware progression replay; accepted changes are also recorded as revisions. Old workouts
created before version 10 retain their actual values but cannot acquire invented set timestamps or
pre-migration event sequences. A pre-version-9 active session has an unknown active duration and
requires an explicit user correction before it can be paused or finished. Older completed sessions
keep their original duration behavior.

## External health context

The separate private `health-connect-exporter` project uses a versioned JSONL payload with
`record_id`, `deletions`, `stale_metrics`, nullable measurements, and local dates. LibreFit can
import that contract v1 through Settings → Data using Android's document picker. It maps numeric
weight, sleep, hydration, nutrition, daily activity, blood pressure, and sparse recovery/body
metrics into external observations. Missing fields remain missing. Date-only values have no
fabricated instant. Stable source IDs make repeated imports idempotent; tombstones delete matching
external observations. Stale markers are kept as events and never converted to zero. This import
does not need Health Connect permissions, a network connection, or a merger of the two apps.
The adapter deliberately leaves the exporter's exercise-session text, mindfulness, and detailed
sleep intervals in their original project; it does not pretend these are GZCLP sets.

## Analysis export

Settings → Data → **Export analysis ZIP** writes a streaming, versioned archive through SAF.
It includes raw JSONL and CSV for every first-party table, a manifest, a generated data dictionary,
`AI_CONTEXT.md`, and derived session/set metrics. Each table is read row by row in one database
read transaction, so memory is bounded by the largest single row. The JSONL and CSV use stable
join keys. Timestamps are identified as UTC epoch milliseconds, and date-only/existing local
times are documented separately. Blank CSV cells and JSON null mean unknown; zero means zero.

Derived volume is the sum of completed, non-warm-up `actualGrams × actualReps`. Session duration
uses persisted active elapsed time, excluding paused intervals; older completed sessions retain
their recorded wall-clock duration. Estimated 1RM uses Epley
`actualGrams × (1 + actualReps / 30)` only for completed working sets with 1–10 reps; it is
clearly labeled as an estimate. The archive can omit notes/cues and external observations. Note
redaction also traverses nested plan and revision snapshots.

The restorable JSON backup is version 2. Version 1 backups remain supported; the migration to
database 10 preserves rows and leaves previously unrecorded set times null.

## Verification and limits

Emulator tests cover schema 8→10 migration, version 1 and 2 backup restore, set timestamps,
no-op revision deduplication, progression revisions, all-set ZIP export and independent ZIP/JSON
readback, custom measurement validation and round trip, privacy filtering, Health Connect
idempotency, missing values, tombstones, and legacy workout revisions. The archive is not itself a
restore format.
An actual 18,268-byte fixture archive was pulled from the emulator and parsed with Python's
`zipfile`, `json`, and `csv` libraries: 1 session, 1 exercise, 5 sets, and 26 revisions joined
correctly, and the exported 960,000 gram-rep volume matched the sum of source set records.

Activity background/foreground callbacks are recorded when Android delivers them. A sudden
process kill may not deliver a background callback; no time is invented to fill that gap.
Device clock times are recorded as observed by Android and are not sensor-verified.
