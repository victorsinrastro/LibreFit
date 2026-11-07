# Training backup format

The app exports a UTF-8 JSON document through Android's document picker. The current backup has
`format: "librefit-training-backup"`, `version: 2`, `roomVersion: 9`, and a `tables` object. Each
table contains an array of raw database rows. Integer load fields are grams. New timestamp fields
are UTC Unix milliseconds; null means the time was not recorded. Binary SQLite values use a
`base64:` prefix and are decoded only for BLOB columns.

Version 2 includes generic LibreFit data, GZCLP program and workout data, training blocks and
plans, revisions, custom measurement definitions, and external observations. It is a complete
restore format, including revision history. The separate [analysis ZIP](TRAINING_DATA_SYSTEM.md)
is optimized for reading and joining data, not restoration.

Restore accepts version 1 backups from database schemas 7 and 8, as well as version 2 from schema
9. Version 1 has no set-action timestamps or revision history; restore leaves those fields unknown
and creates a legacy block and next-session plan. It never fabricates historical events. Schema 7
backups lack nullable progression-replay baselines, so replay reports that it cannot safely infer
them. Restore validates structure, column names, duplicate primary keys, and nonnegative loads
before opening a transaction. All deletes and inserts occur in one transaction; a failure rolls
back to the prior database.

The older workout CSV is a small human-readable list of completed sets. It is not a backup and
does not include the complete event history. Use the analysis ZIP for longitudinal analysis.
