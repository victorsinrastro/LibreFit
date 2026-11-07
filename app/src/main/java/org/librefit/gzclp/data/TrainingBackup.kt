/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import org.librefit.db.AppDatabase

/** Version 2 includes the first-party training ledger and custom observations. */
class TrainingBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    private val tables = listOf("dataset", "workouts", "exercises", "sets", "measurements",
        "gzclp_program", "gzclp_template_exercises", "gzclp_progress", "gzclp_sessions",
        "gzclp_session_exercises", "gzclp_sets", "training_events",
        "training_measurement_definitions", "training_measurements", "training_blocks",
        "training_plans")

    suspend fun export(uri: Uri) {
        val document = database.withTransaction {
            val body = JSONObject().put("format", "librefit-training-backup")
                .put("version", 2).put("roomVersion", 9)
            val data = JSONObject()
            tables.forEach { table ->
                val rows = JSONArray()
                database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for (column in 0 until cursor.columnCount) {
                            val value: Any = when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                Cursor.FIELD_TYPE_BLOB -> "base64:" + Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP)
                                else -> cursor.getString(column)
                            }
                            row.put(cursor.getColumnName(column), value)
                        }
                        rows.put(row)
                    }
                }
                data.put(table, rows)
            }
            body.put("tables", data).toString()
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(document) }
            ?: error("Could not open backup destination")
    }

    suspend fun exportCsv(uri: Uri) {
        val lines = database.withTransaction {
            buildString {
                appendLine("session_id,workout,started_at,exercise,tier,set_type,reps,weight_grams,rir")
                database.openHelper.readableDatabase.query("""
                    SELECT s.id, s.workoutIndex, s.startedAt, e.exerciseName, e.tier,
                        gs.warmup, gs.amrap, gs.actualReps, gs.actualGrams, gs.rir
                    FROM gzclp_sessions s JOIN gzclp_session_exercises e ON e.sessionId=s.id
                    JOIN gzclp_sets gs ON gs.sessionExerciseId=e.id
                    WHERE s.status='COMPLETED' AND gs.completed=1
                    ORDER BY s.startedAt, e.position, gs.position
                """.trimIndent()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val type = if (cursor.getInt(5) != 0) "warmup" else if (cursor.getInt(6) != 0) "amrap" else "working"
                        val fields = listOf(cursor.getLong(0).toString(), cursor.getInt(1).toString(),
                            cursor.getLong(2).toString(), cursor.getString(3), cursor.getString(4), type,
                            cursor.getInt(7).toString(), cursor.getLong(8).toString(),
                            if (cursor.isNull(9)) "" else cursor.getInt(9).toString())
                        appendLine(fields.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                    }
                }
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(lines) }
            ?: error("Could not open CSV destination")
    }

    fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: error("Could not open backup")

    fun preview(document: String): String {
        val data = parse(document)
        val sessions = data.getJSONArray("gzclp_sessions")
        val completed = (0 until sessions.length()).count { sessions.getJSONObject(it).optString("status") == "COMPLETED" }
        return "$completed completed GZCLP workouts, ${data.getJSONArray("gzclp_sets").length()} logged sets. Restoring replaces local app data."
    }

    suspend fun restore(document: String) {
        val data = parse(document) // Validate entirely before beginning the transaction.
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            tables.asReversed().forEach { db.execSQL("DELETE FROM $it") }
            tables.forEach { table ->
                val rows = data.getJSONArray(table)
                val blobColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info($table)").use { columns ->
                    while (columns.moveToNext()) if (columns.getString(2).contains("BLOB", ignoreCase = true))
                        blobColumns += columns.getString(1)
                }
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val values = ContentValues()
                    row.keys().forEach { key ->
                        when (val value = row.get(key)) {
                            JSONObject.NULL -> values.putNull(key)
                            is Int -> values.put(key, value)
                            is Long -> values.put(key, value)
                            is Double -> values.put(key, value)
                            is String -> if (key in blobColumns && value.startsWith("base64:"))
                                values.put(key, Base64.decode(value.removePrefix("base64:"), Base64.DEFAULT))
                                else if (key in blobColumns) error("Invalid blob for $table.$key")
                                else values.put(key, value)
                            else -> error("Invalid value for $table.$key")
                        }
                    }
                    check(db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
                }
            }
            if (data.getJSONArray("training_blocks").length() == 0 &&
                data.getJSONArray("gzclp_program").length() > 0) {
                db.execSQL("INSERT INTO training_blocks (id,programId,startedAtMillis,endedAtMillis,firstWorkoutIndex,rotationJson,initialProgramJson,provenance) SELECT 'restored-legacy-block-1',id,createdAt,NULL,nextWorkoutIndex,'[0,1,2,3]',NULL,'LEGACY' FROM gzclp_program")
                db.execSQL("INSERT INTO training_plans (id,blockId,workoutIndex,createdAtMillis,startedAtMillis,completedAtMillis,sessionId,status,templateSnapshotJson,progressionSnapshotJson,provenance) SELECT 'restored-legacy-plan-1','restored-legacy-block-1',p.nextWorkoutIndex,NULL,s.startedAt,NULL,s.id,CASE WHEN s.id IS NULL THEN 'READY' ELSE 'STARTED' END,NULL,NULL,'LEGACY' FROM gzclp_program p LEFT JOIN gzclp_sessions s ON s.id=(SELECT id FROM gzclp_sessions WHERE status='ACTIVE' ORDER BY startedAt DESC LIMIT 1)")
            }
        }
    }

    private fun parse(document: String): JSONObject {
        require(document.length <= 50_000_000) { "Backup is too large" }
        val root = JSONObject(document)
        val version = root.optInt("version")
        require(root.optString("format") == "librefit-training-backup" && version in 1..2) {
            "Unsupported backup format"
        }
        require(root.optInt("roomVersion") in 7..9 && (version != 2 || root.optInt("roomVersion") == 9)) {
            "This backup uses an incompatible database version"
        }
        val data = root.getJSONObject("tables")
        if (version == 1) tables.drop(11).forEach { data.put(it, JSONArray()) }
        val db = database.openHelper.readableDatabase
        tables.forEach { table ->
            val allowed = mutableSetOf<String>()
            db.query("PRAGMA table_info($table)").use { cursor ->
                while (cursor.moveToNext()) allowed += cursor.getString(1)
            }
            val rows = data.getJSONArray(table)
            val identities = mutableSetOf<String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().all { it in allowed }) { "Unknown $table column" }
                val idKey = if (table == "gzclp_progress") "progressionKey" else if (table == "gzclp_template_exercises") "id" else "id"
                if (row.has(idKey)) require(identities.add(row.get(idKey).toString())) { "Duplicate $table record" }
                row.keys().forEach { field ->
                    if (field.endsWith("Grams") && !row.isNull(field))
                        require(row.opt(field) is Number && row.getLong(field) >= 0) { "Invalid $table.$field" }
                }
            }
        }
        val sessionIds = ids(data, "gzclp_sessions")
        val exerciseIds = ids(data, "gzclp_session_exercises")
        val blockIds = ids(data, "training_blocks")
        val definitionIds = ids(data, "training_measurement_definitions")
        require(references(data, "gzclp_session_exercises", "sessionId", sessionIds)) { "Exercise without workout" }
        require(references(data, "gzclp_sets", "sessionExerciseId", exerciseIds)) { "Set without exercise" }
        require(references(data, "training_plans", "blockId", blockIds)) { "Plan without block" }
        require(references(data, "training_plans", "sessionId", sessionIds, nullable = true)) { "Plan without workout" }
        require(references(data, "training_measurements", "definitionId", definitionIds)) {
            "Observation without definition"
        }
        return data
    }

    private fun ids(data: JSONObject, table: String): Set<String> {
        val rows = data.getJSONArray(table)
        return (0 until rows.length()).map { rows.getJSONObject(it).get("id").toString() }.toSet()
    }

    private fun references(data: JSONObject, table: String, field: String, targets: Set<String>,
        nullable: Boolean = false): Boolean {
        val rows = data.getJSONArray(table)
        return (0 until rows.length()).all { index ->
            val row = rows.getJSONObject(index)
            if (row.isNull(field)) nullable else row.get(field).toString() in targets
        }
    }
}
