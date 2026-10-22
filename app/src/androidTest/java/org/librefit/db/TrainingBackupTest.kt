package org.librefit.db

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.librefit.db.entity.GzclpProgramEntity
import org.librefit.db.entity.GzclpProgressEntity
import org.librefit.db.entity.GzclpTemplateExerciseEntity
import org.librefit.db.repository.GzclpRepository
import org.librefit.gzclp.data.TrainingBackup

@RunWith(AndroidJUnit4::class)
class TrainingBackupTest {
    @Test fun finishedWorkoutAndNextPrescriptionSurviveRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = java.io.File(context.cacheDir, "training-backup-session.json")
        try {
            val repo = GzclpRepository(database, database.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("a1-squat", 0, 0, "squat", "Squat", "T1", "squat:T1")),
                listOf(GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000,
                    incrementGrams = 5_000)))
            val id = repo.startSession(0)
            repo.getSession(id)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            assertTrue(repo.finishSession(id))
            val backup = TrainingBackup(context, database)
            val uri = Uri.fromFile(file)
            backup.export(uri)
            val document = backup.read(uri)
            val before = repo.getSession(id)!!
            val next = database.getGzclpDao().progress("squat:T1")!!.recommendedGrams
            database.getGzclpDao().upsertProgram(GzclpProgramEntity(nextWorkoutIndex = 0))
            database.getGzclpDao().upsertProgress(GzclpProgressEntity("squat:T1", "squat", "T1",
                recommendedGrams = 1_000, incrementGrams = 1_000))
            backup.restore(document)
            assertEquals(1, database.getGzclpDao().program()!!.nextWorkoutIndex)
            assertEquals(next, database.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
            assertEquals(before, repo.getSession(id))
        } finally { database.close(); file.delete() }
    }

    @Test fun roundTripAndRejectedImportPreserveProgram() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = java.io.File(context.cacheDir, "training-backup-test.json")
        try {
            database.getGzclpDao().upsertProgram(GzclpProgramEntity(nextWorkoutIndex = 2))
            val backup = TrainingBackup(context, database)
            val uri = Uri.fromFile(file)
            backup.export(uri)
            val document = backup.read(uri)
            assertTrue(backup.preview(document).contains("completed GZCLP workouts"))
            database.getGzclpDao().upsertProgram(GzclpProgramEntity(nextWorkoutIndex = 0))
            backup.restore(document)
            assertEquals(2, database.getGzclpDao().program()?.nextWorkoutIndex)
            runCatching { backup.restore("{\"format\":\"invalid\"}") }
            assertEquals(2, database.getGzclpDao().program()?.nextWorkoutIndex)
        } finally {
            database.close()
            file.delete()
        }
    }
}
