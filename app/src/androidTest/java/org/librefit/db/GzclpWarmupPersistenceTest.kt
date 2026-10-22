/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db

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

@RunWith(AndroidJUnit4::class)
class GzclpWarmupPersistenceTest {
    @Test fun explicitRegenerationKeepsCompletedWarmupsAndWorkingSets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("a1-squat", 0, 0, "squat", "Squat", "T1", "squat:T1")),
                listOf(GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000,
                    incrementGrams = 5_000)))
            val sessionId = repo.startSession(0)
            val exerciseId = repo.getSession(sessionId)!!.exercises.single().exercise.id
            repo.generateWarmups(exerciseId)
            val original = repo.getSession(sessionId)!!.exercises.single().sets
            val completed = original.first { it.warmup }.copy(completed = true, actualReps = 8,
                actualGrams = 22_500)
            repo.saveSet(completed)
            val workingIds = original.filterNot { it.warmup }.map { it.id }
            repo.generateWarmups(exerciseId, replacePending = true)
            val regenerated = repo.getSession(sessionId)!!.exercises.single().sets
            assertEquals(completed, regenerated.first { it.id == completed.id })
            assertEquals(workingIds, regenerated.filterNot { it.warmup }.map { it.id })
            assertTrue(regenerated.filter { it.warmup && !it.completed }.none { it.id in original.map { old -> old.id } })
        } finally { db.close() }
    }

    @Test fun repeatedGenerationPreservesLoggedAndManualWarmups() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(
                GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("a1-squat", 0, 0, "squat", "Squat", "T1", "squat:T1")),
                listOf(GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000,
                    incrementGrams = 5_000))
            )
            val sessionId = repo.startSession(0)
            val exerciseId = repo.getSession(sessionId)!!.exercises.single().exercise.id
            repo.generateWarmups(exerciseId)
            val first = repo.getSession(sessionId)!!.exercises.single().sets.filter { it.warmup }
            assertTrue(first.isNotEmpty())
            val edited = first.first().copy(completed = true, actualReps = 7, actualGrams = 22_500)
            repo.saveSet(edited)
            repo.generateWarmups(exerciseId)
            val second = repo.getSession(sessionId)!!.exercises.single().sets.filter { it.warmup }
            assertEquals(first.size, second.size)
            assertEquals(edited, second.first { it.id == edited.id })
            assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())

            // A user-created warm-up must also block replacement of the existing warm-up sequence.
            repo.saveSet(first.last().copy(id = 0, position = -1, actualReps = 3,
                actualGrams = 30_000, targetGrams = 30_000))
            val before = repo.getSession(sessionId)!!.exercises.single().sets.filter { it.warmup }
            repo.generateWarmups(exerciseId)
            val after = repo.getSession(sessionId)!!.exercises.single().sets.filter { it.warmup }
            assertEquals(before.toSet(), after.toSet())
        } finally { db.close() }
    }
}
