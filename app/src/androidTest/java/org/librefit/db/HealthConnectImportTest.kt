/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.librefit.gzclp.data.HealthConnectJsonlImport
import org.librefit.gzclp.data.TrainingMeasurements

@RunWith(AndroidJUnit4::class)
class HealthConnectImportTest {
    @Test fun importIsOptionalIdempotentAndHonorsTombstones() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(context.cacheDir, "health-wire-${System.nanoTime()}.jsonl")
        try {
            val importer = HealthConnectJsonlImport(context, db, TrainingMeasurements(db))
            val payload = JSONObject().put("contract_version", "1")
                .put("weight", JSONArray().put(JSONObject().put("date", "2026-10-22")
                    .put("weight_kg", 80.5).put("body_fat_pct", JSONObject.NULL)
                    .put("record_id", "weight-123")))
                .put("daily", JSONArray().put(JSONObject().put("date", "2026-10-22")
                    .put("steps", JSONObject.NULL).put("distance_km", 2.0)))
                .put("stale_metrics", JSONArray().put(JSONObject().put("metric", "steps")
                    .put("reason", "permission denied")))
            file.writeText(payload.toString() + "\n")
            val uri = Uri.fromFile(file)
            assertTrue(importer.preview(uri).contains("2 numeric observations"))
            importer.import(uri)
            importer.import(uri)
            val weightDefinition = db.getTrainingDataDao().definitionByKey("hc_weight_weight_kg")!!
            val weight = db.getTrainingDataDao().externalMeasurement(weightDefinition.id,
                "HEALTH_CONNECT", "weight-123")!!
            assertEquals(80.5, weight.numberValue!!, 0.0)
            assertNull(weight.observedAtMillis) // Date-only source did not provide a precise instant.
            assertEquals(1, db.getTrainingDataDao().allEvents().count {
                it.kind == "MEASUREMENT_RECORDED" && it.subjectId == weight.id })
            assertTrue(db.getTrainingDataDao().allEvents().any { it.kind == "EXTERNAL_STALE_MARKER" })
            assertEquals(1, db.getTrainingDataDao().allEvents().count { it.kind == "EXTERNAL_STALE_MARKER" })
            payload.getJSONArray("weight").getJSONObject(0).put("weight_kg", 81.0)
            file.writeText(payload.toString() + "\n")
            importer.import(uri)
            assertEquals(81.0, db.getTrainingDataDao().measurement(weight.id)!!.numberValue!!, 0.0)
            payload.remove("weight")
            payload.put("deletions", JSONArray().put(JSONObject().put("record_id", "weight-123")))
            file.writeText(payload.toString() + "\n")
            importer.import(uri)
            assertNull(db.getTrainingDataDao().measurement(weight.id))
        } finally { db.close(); file.delete() }
    }
}
