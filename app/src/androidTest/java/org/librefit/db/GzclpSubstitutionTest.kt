/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.librefit.db.entity.GzclpProgramEntity
import org.librefit.db.entity.GzclpProgressEntity
import org.librefit.db.entity.GzclpTemplateExerciseEntity
import org.librefit.db.repository.GzclpRepository

@RunWith(AndroidJUnit4::class)
class GzclpSubstitutionTest {
    @Test fun temporaryReplacementAndUndoNeverAdvanceOriginalLift() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("a1", 0, 0, "bench", "Bench Press", "T1", "bench:T1")),
                listOf(GzclpProgressEntity("bench:T1", "bench", "T1", recommendedGrams = 60_000,
                    incrementGrams = 2_500)))
            val session = repo.startSession(0)
            val exercise = repo.getSession(session)!!.exercises.single().exercise.id
            repo.substituteSessionExercise(exercise, "dumbbell_bench", "Dumbbell Bench Press", 40_000)
            var item = repo.getSession(session)!!.exercises.single()
            assertTrue(item.exercise.progressionKey.startsWith("session:"))
            assertTrue(item.sets.all { it.targetGrams == 40_000L })
            repo.restoreSessionExercise(exercise)
            item = repo.getSession(session)!!.exercises.single()
            assertEquals("bench:T1", item.exercise.progressionKey)
            assertTrue(item.sets.all { it.targetGrams == 60_000L })
            repo.substituteSessionExercise(exercise, "dumbbell_bench", "Dumbbell Bench Press", 40_000)
            item = repo.getSession(session)!!.exercises.single()
            item.sets.forEach { repo.saveSet(it.copy(completed = true)) }
            assertTrue(runCatching { repo.restoreSessionExercise(exercise) }.isFailure)
            repo.finishSession(session)
            assertEquals(60_000L, db.getGzclpDao().progress("bench:T1")!!.recommendedGrams)
            assertEquals(40_000L, repo.getSession(session)!!.exercises.single().sets.first().actualGrams)
        } finally { db.close() }
    }

    @Test fun futureReplacementGetsIndependentStartingStateAndPreservesActivePrescription() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("a1", 0, 0, "bench", "Bench Press", "T1", "bench:T1")),
                listOf(GzclpProgressEntity("bench:T1", "bench", "T1", recommendedGrams = 60_000,
                    incrementGrams = 2_500)))
            val session = repo.startSession(0)
            repo.replaceTemplateExercise("a1", "dumbbell_bench", "Dumbbell Bench Press", 35_000)
            assertEquals("bench:T1", repo.getSession(session)!!.exercises.single().exercise.progressionKey)
            assertEquals(60_000L, db.getGzclpDao().progress("bench:T1")!!.recommendedGrams)
            assertEquals(35_000L, db.getGzclpDao().progress("dumbbell_bench:T1")!!.recommendedGrams)
            assertEquals("dumbbell_bench:T1", db.getGzclpDao().templatesForWorkout(0).single().progressionKey)
            val activeExerciseId = repo.getSession(session)!!.exercises.single().exercise.id
            assertTrue(runCatching { repo.substituteSessionExercise(activeExerciseId,
                "chest_press", "Chest Press", 30_000) }.isFailure)
            assertEquals("bench:T1", repo.getSession(session)!!.exercises.single().exercise.progressionKey)
        } finally { db.close() }
    }
}
