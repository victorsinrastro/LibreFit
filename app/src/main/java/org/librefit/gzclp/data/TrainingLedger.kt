/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.data

import android.database.Cursor
import android.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import org.librefit.db.AppDatabase
import org.librefit.db.entity.TrainingEventEntity

/** Called inside the same Room transaction as the state change it describes. */
class TrainingLedger(private val database: AppDatabase) {
    private val allowedTables = setOf("gzclp_program", "gzclp_template_exercises", "gzclp_progress",
        "gzclp_sessions", "gzclp_session_exercises", "gzclp_sets",
        "training_measurement_definitions", "training_measurements", "training_blocks",
        "training_plans", "workouts", "exercises", "sets")

    fun workoutSnapshot(id: Long): String? {
        val workout = snapshot("workouts", id.toString()) ?: return null
        val exercises = JSONArray()
        val sets = JSONArray()
        database.openHelper.readableDatabase.query("SELECT id FROM exercises WHERE workoutId = ? ORDER BY id",
            arrayOf(id.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val exerciseId = cursor.getLong(0)
                snapshot("exercises", exerciseId.toString())?.let { exercises.put(JSONObject(it)) }
                database.openHelper.readableDatabase.query("SELECT id FROM sets WHERE exerciseId = ? ORDER BY id",
                    arrayOf(exerciseId.toString())).use { setCursor ->
                    while (setCursor.moveToNext()) snapshot("sets", setCursor.getLong(0).toString())
                        ?.let { sets.put(JSONObject(it)) }
                }
            }
        }
        return JSONObject().put("workout", JSONObject(workout)).put("exercises", exercises)
            .put("sets", sets).toString()
    }

    suspend fun recordAggregate(kind: String, table: String, id: String, before: String?,
        after: String?, reason: String? = null) {
        require(table in allowedTables)
        if (before == after) return
        val now = System.currentTimeMillis()
        database.getTrainingDataDao().insertEvent(TrainingEventEntity(UUID.randomUUID().toString(),
            kind, table, id, observedAtMillis = now, recordedAtMillis = now,
            beforeJson = before, afterJson = after, reason = reason))
    }

    fun snapshot(table: String, id: String): String? {
        require(table in allowedTables)
        val key = when (table) {
            "gzclp_progress" -> "progressionKey"
            else -> "id"
        }
        database.openHelper.readableDatabase.query("SELECT * FROM $table WHERE $key = ?", arrayOf(id))
            .use { cursor ->
                if (!cursor.moveToFirst()) return null
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
                return row.toString()
            }
    }

    suspend fun record(kind: String, table: String, id: String, sessionId: Long? = null,
        before: String?, reason: String? = null, observedAtMillis: Long? = System.currentTimeMillis()) {
        val after = snapshot(table, id)
        if (before == after) return // A retried write that did not change state creates no second event.
        database.getTrainingDataDao().insertEvent(TrainingEventEntity(
            id = UUID.randomUUID().toString(), kind = kind, subjectType = table, subjectId = id,
            sessionId = sessionId, observedAtMillis = observedAtMillis,
            recordedAtMillis = System.currentTimeMillis(), beforeJson = before, afterJson = after,
            reason = reason, timeQuality = if (observedAtMillis == null) "UNKNOWN" else "OBSERVED"))
    }

    suspend fun note(kind: String, table: String, id: String, sessionId: Long? = null,
        reason: String? = null, observedAtMillis: Long? = System.currentTimeMillis(),
        provenance: String = "LIBREFIT", idempotencyKey: String? = null) {
        require(table in allowedTables)
        database.getTrainingDataDao().insertEvent(TrainingEventEntity(
            id = idempotencyKey?.let { UUID.nameUUIDFromBytes(it.toByteArray(Charsets.UTF_8)).toString() }
                ?: UUID.randomUUID().toString(), kind = kind, subjectType = table, subjectId = id,
            sessionId = sessionId, observedAtMillis = observedAtMillis,
            recordedAtMillis = System.currentTimeMillis(), reason = reason,
            provenance = provenance, timeQuality = if (observedAtMillis == null) "UNKNOWN" else "OBSERVED"))
    }
}
