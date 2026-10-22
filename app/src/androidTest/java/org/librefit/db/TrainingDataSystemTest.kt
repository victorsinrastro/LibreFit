/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.librefit.db.entity.GzclpProgramEntity
import org.librefit.db.entity.GzclpProgressEntity
import org.librefit.db.entity.GzclpTemplateExerciseEntity
import org.librefit.db.repository.GzclpRepository
import org.librefit.db.repository.WorkoutRepository
import org.librefit.db.entity.Workout
import org.librefit.gzclp.data.TrainingAnalysisExport
import org.librefit.gzclp.data.TrainingBackup
import org.librefit.gzclp.data.TrainingMeasurements

@RunWith(AndroidJUnit4::class)
class TrainingDataSystemTest {
    @Test fun legacyWorkoutEditsRetainAggregateRevisions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val workout = Workout(title = "First title")
            val id = db.getWorkoutDao().addWorkout(workout)
            val repository = WorkoutRepository(db.getWorkoutDao(), db)
            repository.updateWorkout(workout.copy(id = id, title = "Corrected title"))
            val edit = db.getTrainingDataDao().allEvents().single { it.kind == "LEGACY_WORKOUT_EDITED" }
            assertTrue(edit.beforeJson!!.contains("First title"))
            assertTrue(edit.afterJson!!.contains("Corrected title"))
            repository.deleteWorkout(workout.copy(id = id, title = "Corrected title"))
            assertTrue(db.getTrainingDataDao().allEvents().any { it.kind == "LEGACY_WORKOUT_DELETED" })
        } finally { db.close() }
    }

    @Test fun setActionsAndProgressionAreExportedWithRealTimes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(context.cacheDir, "training-analysis-${System.nanoTime()}.zip")
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("squat-a1", 0, 0, "squat", "Squat", "T1", "squat:T1",
                    cue = "PRIVATE_STANCE_CUE")),
                listOf(GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000,
                    incrementGrams = 5_000)))
            val sessionId = repo.startSession(0)
            repo.saveSession(repo.getSession(sessionId)!!.session.copy(restDeadlineMillis =
                System.currentTimeMillis() + 180_000))
            assertTrue(db.getTrainingDataDao().eventsForSession(sessionId).any { it.kind == "REST_CHANGED" })
            val first = repo.getSession(sessionId)!!.exercises.single().sets.first()
            assertNull(first.completedAtMillis)
            val before = System.currentTimeMillis()
            repo.saveSet(first.copy(completed = true, actualReps = 4, completedAtMillis = before))
            val after = System.currentTimeMillis()
            val completed = db.getGzclpDao().set(first.id)!!
            assertEquals(before, completed.completedAtMillis)
            assertTrue(completed.completedAtMillis!! in before..after)
            repo.saveSet(completed.copy(completed = true, actualReps = 4))
            assertEquals(1, db.getTrainingDataDao().eventsForSession(sessionId)
                .count { it.kind == "SET_COMPLETED" && it.subjectId == first.id.toString() })
            repo.saveSet(completed.copy(completed = false))
            assertNull(db.getGzclpDao().set(first.id)!!.completedAtMillis)
            repo.getSession(sessionId)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            assertTrue(repo.finishSession(sessionId))
            assertFalse(repo.finishSession(sessionId))
            assertEquals(1, db.getTrainingDataDao().eventsForSession(sessionId).count { it.kind == "SESSION_COMPLETED" })
            assertTrue(db.getTrainingDataDao().eventsForSession(sessionId).any { it.kind == "PROGRESSION_APPLIED" })
            assertEquals(1, db.getTrainingDataDao().readyPlan()!!.workoutIndex)

            TrainingAnalysisExport(context, db).export(Uri.fromFile(file))
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(names.containsAll(listOf("manifest.json", "AI_CONTEXT.md", "data_dictionary.csv",
                    "raw/gzclp_sets.jsonl", "csv/training_events.csv", "derived/session_metrics.csv")))
                val sets = zip.getInputStream(zip.getEntry("raw/gzclp_sets.jsonl")).bufferedReader()
                    .readLines().map(::JSONObject)
                assertEquals(repo.getSession(sessionId)!!.exercises.single().sets.size, sets.size)
                val exportedFirst = sets.first { it.getLong("id") == first.id }
                assertEquals(4, exportedFirst.getInt("actualReps"))
                assertTrue(exportedFirst.getLong("completedAtMillis") in before..System.currentTimeMillis())
                val metrics = zip.getInputStream(zip.getEntry("derived/session_metrics.csv"))
                    .bufferedReader().readText()
                assertTrue(metrics.contains(sessionId.toString()))
            }
            if (InstrumentationRegistry.getArguments().getString("persistAnalysisFixture") == "true") {
                file.copyTo(File(context.getExternalFilesDir(null), "training-analysis-fixture.zip"),
                    overwrite = true)
            }
            TrainingAnalysisExport(context, db).export(Uri.fromFile(file),
                TrainingAnalysisExport.Privacy(includeNotes = false))
            ZipFile(file).use { zip ->
                val plans = zip.getInputStream(zip.getEntry("raw/training_plans.jsonl"))
                    .bufferedReader().readText()
                val events = zip.getInputStream(zip.getEntry("raw/training_events.jsonl"))
                    .bufferedReader().readText()
                assertFalse(plans.contains("PRIVATE_STANCE_CUE"))
                assertFalse(events.contains("PRIVATE_STANCE_CUE"))
            }
            val historical = db.getGzclpDao().set(first.id)!!
            repo.saveSet(historical.copy(actualReps = 5))
            val correction = db.getTrainingDataDao().eventsForSession(sessionId)
                .last { it.kind == "HISTORICAL_SET_CORRECTED" && it.subjectId == first.id.toString() }
            assertEquals(4, JSONObject(correction.beforeJson!!).getInt("actualReps"))
            assertEquals(5, JSONObject(correction.afterJson!!).getInt("actualReps"))
        } finally { db.close(); file.delete() }
    }

    @Test fun customMeasurementsAndLegacyBackupRoundTripWithoutExternalDependency() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val backupFile = File(context.cacheDir, "training-backup-${System.nanoTime()}.json")
        val archive = File(context.cacheDir, "training-private-${System.nanoTime()}.zip")
        try {
            val measurements = TrainingMeasurements(db)
            val definitionId = measurements.define("readiness", "Readiness", "NUMBER", "score", 1.0, 5.0,
                "PRE_POST", "User-reported readiness before training")
            assertTrue(runCatching { measurements.record(definitionId, "DAY", "2026-10-22",
                System.currentTimeMillis(), numberValue = 7.0) }.isFailure)
            val id = measurements.record(definitionId, "DAY", "2026-10-22", System.currentTimeMillis(),
                numberValue = 4.0, quality = "SELF_REPORTED")
            val externalDefinition = measurements.define("sleep_minutes", "Sleep", "NUMBER", "min", 0.0, 1440.0,
                "EXTERNAL")
            measurements.record(externalDefinition, "DAY", "2026-10-22", System.currentTimeMillis(),
                numberValue = 420.0, provenance = "HEALTH_CONNECT", sourceRecordId = "hc-123", quality = "IMPORTED")
            assertEquals(4.0, db.getTrainingDataDao().measurement(id)!!.numberValue!!, 0.0)
            val backup = TrainingBackup(context, db)
            val uri = Uri.fromFile(backupFile)
            backup.export(uri)
            val document = backup.read(uri)
            assertEquals(2, JSONObject(document).getInt("version"))
            measurements.deleteObservation(id)
            backup.restore(document)
            assertEquals(4.0, db.getTrainingDataDao().measurement(id)!!.numberValue!!, 0.0)
            val invalid = JSONObject(document)
            invalid.getJSONObject("tables").getJSONArray("training_measurements")
                .getJSONObject(0).put("definitionId", "missing-definition")
            assertTrue(runCatching { backup.restore(invalid.toString()) }.isFailure)
            assertEquals(4.0, db.getTrainingDataDao().measurement(id)!!.numberValue!!, 0.0)
            TrainingAnalysisExport(context, db).export(Uri.fromFile(archive),
                TrainingAnalysisExport.Privacy(includeNotes = false, includeExternal = false))
            ZipFile(archive).use { zip ->
                val lines = zip.getInputStream(zip.getEntry("raw/training_measurements.jsonl"))
                    .bufferedReader().readLines()
                assertEquals(1, lines.size)
                assertEquals(id, JSONObject(lines.single()).getString("id"))
                val events = zip.getInputStream(zip.getEntry("raw/training_events.jsonl"))
                    .bufferedReader().readText()
                assertFalse(events.contains("hc-123"))
            }
            val legacy = JSONObject(document).put("version", 1).put("roomVersion", 8)
            val tables = legacy.getJSONObject("tables")
            tables.remove("training_events")
            tables.remove("training_measurement_definitions")
            tables.remove("training_measurements")
            tables.remove("training_blocks")
            tables.remove("training_plans")
            backup.restore(legacy.toString())
            assertTrue(db.getTrainingDataDao().definitions().isEmpty())
        } finally { db.close(); backupFile.delete(); archive.delete() }
    }
}
