/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.librefit.db.AppDatabase

/** Optional file adapter for the separate health-connect-exporter incoming.jsonl contract v1. */
class HealthConnectJsonlImport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val measurements: TrainingMeasurements
) {
    private data class Field(val family: String, val name: String, val label: String, val unit: String)
    private val fields = listOf(
        Field("weight", "weight_kg", "Body weight", "kg"),
        Field("weight", "body_fat_pct", "Body fat", "%"),
        Field("sleep", "total_min", "Sleep", "min"),
        Field("sleep", "light_min", "Light sleep", "min"),
        Field("sleep", "deep_min", "Deep sleep", "min"),
        Field("sleep", "rem_min", "REM sleep", "min"),
        Field("hydration", "liters", "Hydration", "L"),
        Field("nutrition", "energy_kcal", "Energy", "kcal"),
        Field("nutrition", "protein_g", "Protein", "g"),
        Field("nutrition", "carbs_g", "Carbohydrates", "g"),
        Field("nutrition", "fat_g", "Fat", "g"),
        Field("daily", "steps", "Steps", "count"),
        Field("daily", "distance_km", "Distance", "km"),
        Field("daily", "active_cal", "Active energy", "kcal"),
        Field("bp", "systolic", "Systolic pressure", "mmHg"),
        Field("bp", "diastolic", "Diastolic pressure", "mmHg"),
        Field("bp", "pulse", "Pulse", "bpm"),
        Field("vo2max", "vo2_ml_kg_min", "VO2 max", "mL/kg/min"),
        Field("glucose", "mg_dl", "Blood glucose", "mg/dL"),
        Field("temperature", "celsius", "Body temperature", "°C"),
        Field("skin_temperature", "baseline_celsius", "Skin temperature", "°C"),
        Field("respiratory", "breaths_per_min", "Respiratory rate", "breaths/min"),
        Field("hrv", "rmssd_ms", "HRV RMSSD", "ms"),
        Field("composition", "water_kg", "Body water", "kg"),
        Field("composition", "bone_kg", "Bone mass", "kg"),
        Field("composition", "lean_kg", "Lean mass", "kg")
    )
    private val ledger = TrainingLedger(database)

    suspend fun preview(uri: Uri): String = withContext(Dispatchers.IO) {
        var chunks = 0
        var values = 0
        var deletions = 0
        readLines(uri) { payload ->
            chunks++
            for (field in fields) {
                val rows = payload.optJSONArray(field.family) ?: continue
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    if (!row.isNull(field.name) && row.opt(field.name) != null) {
                        require(row.opt(field.name) is Number) { "Invalid ${field.family}.${field.name}" }
                        values++
                    }
                }
            }
            deletions += payload.optJSONArray("deletions")?.length() ?: 0
        }
        require(chunks > 0) { "No exporter payloads found" }
        "$chunks chunks, $values numeric observations, $deletions source deletions. Importing adds or updates external context; it does not change GZCLP workouts."
    }

    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        preview(uri) // Validate the readable file before opening the write transaction.
        var processed = 0
        database.withTransaction {
            readLines(uri) { payload ->
                for (field in fields) {
                    val rows = payload.optJSONArray(field.family) ?: continue
                    val definitionId = measurements.ensureExternalDefinition(
                        "hc_${field.family}_${field.name}", field.label, field.unit)
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        val raw = row.opt(field.name)
                        if (raw == null || raw == JSONObject.NULL) continue
                        val number = (raw as? Number)?.toDouble() ?: error("Invalid health measurement")
                        require(number.isFinite())
                        val date = row.optString("date").ifBlank { row.optString("start") }
                        require(date.length >= 10) { "Health row has no date" }
                        val subjectId = date.take(10)
                        val sourceId = row.optString("record_id").takeIf { it.isNotBlank() }
                            ?: if (field.family == "daily") "daily:$subjectId"
                            else "legacy:${field.family}:${sha256("$subjectId|${field.name}|$number")}"
                        val observed = parseInstant(row.optString("sleep_end").ifBlank { date })
                        measurements.record(definitionId, "DAY", subjectId, observed,
                            numberValue = number, provenance = "HEALTH_CONNECT",
                            sourceRecordId = sourceId, quality = "IMPORTED")
                        processed++
                    }
                }
                payload.optJSONArray("deletions")?.let { removals ->
                    for (index in 0 until removals.length()) {
                        val sourceId = removals.getJSONObject(index).getString("record_id")
                        measurements.deleteExternalRecord(sourceId)
                    }
                }
                payload.optJSONArray("stale_metrics")?.let { stale ->
                    for (index in 0 until stale.length()) {
                        val marker = stale.getJSONObject(index)
                        ledger.note("EXTERNAL_STALE_MARKER", "training_measurements",
                            marker.getString("metric"), reason = marker.optString("reason"),
                            observedAtMillis = null, provenance = "HEALTH_CONNECT",
                            idempotencyKey = "hc-stale:${payload.optString("chunk_id")}:${marker}")
                    }
                }
            }
        }
        processed
    }

    private suspend fun readLines(uri: Uri, consume: suspend (JSONObject) -> Unit) {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Could not open health export")
        stream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                require(line.length <= 10_000_000) { "Health export chunk is too large" }
                if (line.isBlank()) continue
                val payload = JSONObject(line)
                require(payload.optString("contract_version") == "1") { "Unsupported health export contract" }
                consume(payload)
            }
        }
    }

    private fun parseInstant(value: String): Long? = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
