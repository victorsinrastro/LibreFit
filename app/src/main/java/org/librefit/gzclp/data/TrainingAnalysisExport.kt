/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import java.time.Instant
import kotlin.math.roundToLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.librefit.db.AppDatabase

/** Bounded-memory, first-party analysis archive. The database remains authoritative. */
class TrainingAnalysisExport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    data class Privacy(val includeNotes: Boolean = true, val includeExternal: Boolean = false)

    private val tables = listOf("dataset", "workouts", "exercises", "sets", "measurements",
        "gzclp_program", "gzclp_template_exercises", "gzclp_progress", "gzclp_sessions",
        "gzclp_session_exercises", "gzclp_sets", "training_events",
        "training_measurement_definitions", "training_measurements", "training_blocks",
        "training_plans")

    suspend fun export(uri: Uri, privacy: Privacy = Privacy()) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open export destination")
        output.use { stream -> ZipOutputStream(stream.buffered()).use { zip ->
            // The read transaction gives every file in the archive the same database snapshot.
            database.withTransaction {
                entry(zip, "manifest.json") { out ->
                    out.write(JSONObject().put("format", "librefit-training-analysis")
                        .put("schemaVersion", 1).put("databaseVersion", 9)
                        .put("exportedAtUtc", Instant.now().toString())
                        .put("notesIncluded", privacy.includeNotes)
                        .put("externalIncluded", privacy.includeExternal)
                        .put("rowFormat", "JSON Lines and RFC 4180-style CSV, UTF-8")
                        .toString().toByteArray(Charsets.UTF_8))
                }
                entry(zip, "AI_CONTEXT.md") { out -> out.write(contextText.toByteArray(Charsets.UTF_8)) }
                entry(zip, "data_dictionary.csv") { out ->
                    out.write("table,column,sqlite_type,nullable,meaning\n".toByteArray())
                    tables.forEach { table ->
                        database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(1)
                                val meaning = when {
                                    name.endsWith("Millis") || name in setOf("startedAt", "finishedAt", "updatedAt", "createdAt") ->
                                        "UTC Unix epoch milliseconds; null means not recorded"
                                    name.endsWith("Grams") -> "integer mass in grams; total barbell load unless equipment says otherwise"
                                    name == "beforeJson" || name == "afterJson" -> "raw row snapshot at revision boundary"
                                    name == "provenance" -> "source of observation"
                                    name == "quality" || name == "timeQuality" -> "observation quality"
                                    else -> "stored first-party value; see AI_CONTEXT.md"
                                }
                                out.write(csv(listOf(table, name, cursor.getString(2),
                                    (cursor.getInt(3) == 0).toString(), meaning)).toByteArray())
                            }
                        }
                    }
                    out.write(csv(listOf("derived_session_metrics", "working_volume_gram_reps", "INTEGER",
                        "false", "sum(actualGrams * actualReps) over completed non-warm-up sets")).toByteArray())
                    out.write(csv(listOf("derived_set_metrics", "estimated_1rm_grams", "INTEGER",
                        "true", "Epley actualGrams * (1 + actualReps / 30), completed working sets with 1 to 10 reps only; estimate")).toByteArray())
                }
                tables.forEach { table ->
                    entry(zip, "raw/$table.jsonl") { out -> forEachRow(table, privacy) { row ->
                        out.write(row.toString().toByteArray(Charsets.UTF_8)); out.write('\n'.code)
                    } }
                    entry(zip, "csv/$table.csv") { out ->
                        val names = columnNames(table)
                        out.write(csv(names).toByteArray(Charsets.UTF_8))
                        forEachRow(table, privacy) { row ->
                            out.write(csv(names.map { name ->
                                if (row.isNull(name)) "" else row.opt(name)?.toString().orEmpty()
                            }).toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                entry(zip, "derived/session_metrics.csv") { out ->
                    out.write("session_id,working_sets,working_volume_gram_reps,duration_ms\n".toByteArray())
                    database.openHelper.readableDatabase.query("""
                        SELECT s.id, COUNT(gs.id), COALESCE(SUM(gs.actualGrams * gs.actualReps), 0),
                            COALESCE(s.activeElapsedMillis, s.finishedAt - s.startedAt)
                        FROM gzclp_sessions s
                        LEFT JOIN gzclp_session_exercises e ON e.sessionId = s.id
                        LEFT JOIN gzclp_sets gs ON gs.sessionExerciseId = e.id AND gs.completed = 1 AND gs.warmup = 0
                        WHERE s.status = 'COMPLETED' GROUP BY s.id ORDER BY s.startedAt, s.id
                    """.trimIndent()).use { cursor -> while (cursor.moveToNext()) {
                        out.write(csv(listOf(cursor.getLong(0).toString(), cursor.getLong(1).toString(),
                            cursor.getLong(2).toString(), if (cursor.isNull(3)) "" else cursor.getLong(3).toString())).toByteArray())
                    } }
                }
                entry(zip, "derived/set_metrics.csv") { out ->
                    out.write("set_id,session_id,estimated_1rm_grams\n".toByteArray())
                    database.openHelper.readableDatabase.query("""
                        SELECT gs.id, s.id, gs.actualGrams, gs.actualReps
                        FROM gzclp_sets gs JOIN gzclp_session_exercises e ON e.id = gs.sessionExerciseId
                        JOIN gzclp_sessions s ON s.id = e.sessionId
                        WHERE s.status = 'COMPLETED' AND gs.completed = 1 AND gs.warmup = 0
                        ORDER BY s.startedAt, gs.id
                    """.trimIndent()).use { cursor -> while (cursor.moveToNext()) {
                        val reps = cursor.getInt(3)
                        val estimate = if (reps in 1..10) (cursor.getLong(2) * (1.0 + reps / 30.0)).roundToLong()
                            .toString() else ""
                        out.write(csv(listOf(cursor.getLong(0).toString(), cursor.getLong(1).toString(),
                            estimate)).toByteArray())
                    } }
                }
            }
        } }
    }

    private fun entry(zip: ZipOutputStream, name: String, write: (ZipOutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        write(zip)
        zip.closeEntry()
    }

    private fun columnNames(table: String): List<String> {
        val names = mutableListOf<String>()
        database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(1)
        }
        return names
    }

    private fun forEachRow(table: String, privacy: Privacy, consume: (JSONObject) -> Unit) {
        require(table in tables)
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (column in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(column)
                    val value: Any = when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> "base64:" + Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP)
                        else -> cursor.getString(column)
                    }
                    row.put(name, value)
                }
                if (!privacy.includeExternal && table == "training_measurements" &&
                    row.optString("provenance") in setOf("HEALTH_CONNECT", "OTHER_EXTERNAL")) continue
                if (!privacy.includeExternal && table == "training_events" &&
                    (row.optString("provenance") in setOf("HEALTH_CONNECT", "OTHER_EXTERNAL") ||
                    listOf("beforeJson", "afterJson").any { field ->
                        row.optString(field).contains("\"provenance\":\"HEALTH_CONNECT\"") ||
                            row.optString(field).contains("\"provenance\":\"OTHER_EXTERNAL\"")
                    })) continue
                if (!privacy.includeNotes) redactNotes(row)
                consume(row)
            }
        }
    }

    private fun redactNotes(value: JSONObject) {
        for (name in value.keys()) {
            if (name in setOf("notes", "cue", "description", "textValue")) {
                value.put(name, JSONObject.NULL)
                continue
            }
            val nested = value.opt(name)
            if (nested is JSONObject) redactNotes(nested)
            else if (nested is org.json.JSONArray) redactNotes(nested)
            else if (nested is String && (nested.startsWith("{") || nested.startsWith("["))) {
                val parsed = runCatching { org.json.JSONTokener(nested).nextValue() }.getOrNull()
                when (parsed) {
                    is JSONObject -> { redactNotes(parsed); value.put(name, parsed.toString()) }
                    is org.json.JSONArray -> { redactNotes(parsed); value.put(name, parsed.toString()) }
                }
            }
        }
    }

    private fun redactNotes(value: org.json.JSONArray) {
        for (index in 0 until value.length()) {
            when (val item = value.opt(index)) {
                is JSONObject -> redactNotes(item)
                is org.json.JSONArray -> redactNotes(item)
            }
        }
    }

    private fun csv(fields: List<String>): String = fields.joinToString(",") {
        "\"${it.replace("\"", "\"\"")}" + "\""
    } + "\r\n"

    private val contextText = """# LibreFit training analysis export

Schema version 1. `raw/*.jsonl` contains one JSON object per authoritative database row;
`csv/*.csv` contains the same rows. Join GZCLP sessions → session exercises → sets using
`gzclp_sessions.id = gzclp_session_exercises.sessionId` and
`gzclp_session_exercises.id = gzclp_sets.sessionExerciseId`. The `training_events` file holds
ordered revisions by `recordedAtMillis`; `beforeJson` and `afterJson` are row snapshots.
Event IDs are UUIDs. Legacy rows from before database version 9 have no synthetic event history.
`training_blocks` and `training_plans` preserve block and upcoming-session snapshots. A plan is
what was expected at creation time; the actual session may differ after a later program edit.

Mass columns ending in `Grams` are integer grams. `loadMeaning` describes whether the value is
total barbell, per dumbbell, machine display, cable stack or bodyweight; null means unknown.
`targetGrams` is prescribed; `actualGrams`
is entered performance. `recommendedGrams` is a program recommendation, not a performed set.
T1 and T2 use independent `progressionKey` values. Warm-ups have `warmup=1` and do not
count toward GZCLP progression or the derived working volume. AMRAP sets have `amrap=1`.
`completedAtMillis` is the device clock time when completion was first recorded; null means
uncompleted or legacy time unknown. `createdAtMillis` and `lastEditedAtMillis` follow the same
rule. `startedAt`, `finishedAt`, and `activeSinceMillis` are UTC Unix epoch milliseconds;
`activeElapsedMillis` is a duration in milliseconds. Legacy LibreFit local
date-time strings have no reliable time zone and must not be treated as UTC.

Missing values are JSON null or an empty CSV field; zero is a real recorded zero. Values in
`derived/session_metrics.csv` are reproducible: sum completed, non-warm-up actual grams × reps
for each completed session. Duration uses the recorded active elapsed time in milliseconds; legacy
completed sessions without that field fall back to finishedAt minus startedAt. `derived/set_metrics.csv`
uses Epley estimated 1RM, actual grams × (1 + reps/30),
only for completed working sets of 1–10 reps; other estimates are missing, not zero.
`training_measurement_definitions` declares custom field type, unit, range and collection mode.
`training_measurements` distinguishes user, LibreFit, and external provenance and quality.
No Health Connect data is needed to interpret first-party workouts.
""".trimIndent()
}
