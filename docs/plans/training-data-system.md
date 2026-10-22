# Training data system implementation plan

## Goal

Bring durable workout history and the useful Health Connect exporter workflow into LibreFit while
keeping the Android Room database authoritative for prescriptions, performed sets, and progression.

## Plan

1. Add append-only training revisions, stable training blocks and plans, typed custom measurements,
   and optional provenance and time-quality fields. Keep legacy numeric session and set IDs stable.
2. Record meaningful workout edits in the same database transaction as the changed row. Preserve
   actual set values, distinguish load meaning, and leave historical timestamps unknown when they
   were not recorded.
3. Import exporter contract v1 JSONL through the document picker. Preserve missing values, local
   dates, tombstones, source IDs, and stale markers; make repeated imports idempotent.
4. Export a streaming analysis ZIP with raw JSONL and CSV, a manifest, a data dictionary, and
   derived set and session metrics. Keep this export separate from the restorable backup format.
5. Expose import, export, optional measurements, and ledger controls in Settings → Data.
6. Migrate from schema 9 to schema 10 so the session timer migration remains at schema 9, then
   retain migration coverage from schema 8 and the legacy schema 4 path.

## Acceptance criteria

- Existing workouts and progression survive the migration without fabricated set times.
- Exported tables join through stable IDs, and derived metrics match the source records.
- Health Connect imports preserve nulls and date-only values, deduplicate stable source IDs, and
  apply tombstones without requiring Health Connect permissions or a network connection.
- Users can restore supported backups independently of the analysis ZIP.
- CI passes Android lint, unit tests, APK build, and SonarCloud analysis.
