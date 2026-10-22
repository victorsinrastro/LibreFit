# Training backup format

The app exports a UTF-8 JSON document through Android's document picker. Format version 1 contains `format: "librefit-training-backup"`, `version: 1`, `roomVersion: 8`, and a `tables` object. Each table is an array of rows whose keys are the Room column names. Weight fields are integer grams. Timestamps are Unix milliseconds. Binary SQLite values, if any, use a `base64:` prefix.

Version 1 includes LibreFit's workout, exercise, set, measurement, and exercise dataset tables, plus all GZCLP program, template, progression, session, exercise, and set tables. The backup is local and contains no account credentials.

Restore accepts version 1 from Room schemas 7 and 8. Schema 7 backups lack nullable progression-replay baselines; those historical sessions remain viewable, and replay reports that their baseline cannot be inferred safely. It validates table names, column names, duplicate primary keys, and nonnegative GZCLP weights before opening a transaction. Within the transaction it replaces the listed tables in dependency order. A failed insert rolls back the transaction and leaves the prior database intact. Future schema changes must add a backup migration before accepting older backup versions.

CSV export is a separate, human-readable log of completed GZCLP sets. Its weight column is always in grams; it is not a restorable backup.
