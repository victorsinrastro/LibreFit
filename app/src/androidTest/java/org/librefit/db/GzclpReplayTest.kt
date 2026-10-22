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
class GzclpReplayTest {
    @Test fun correctionAfterConfirmedResetRequiresReviewAndPreservesCurrentLoad() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(), (0..2).map { day ->
                GzclpTemplateExerciseEntity("day$day", day, 0, "squat", "Squat", "T1", "squat:T1")
            }, listOf(GzclpProgressEntity("squat:T1", "squat", "T1",
                recommendedGrams = 60_000, incrementGrams = 5_000)))
            var third = 0L
            for (day in 0..2) {
                val id = repo.startSession(day)
                val sets = repo.getSession(id)!!.exercises.single().sets.sortedBy { it.position }
                sets.forEachIndexed { index, set ->
                    repo.saveSet(set.copy(completed = true,
                        actualReps = if (index == 0) set.targetReps - 1 else set.targetReps))
                }
                assertTrue(repo.finishSession(id))
                if (day == 2) third = id
            }
            assertTrue(db.getGzclpDao().progress("squat:T1")!!.pendingReset)
            repo.confirmT1Reset("squat:T1", 100_000)
            val afterReset = db.getGzclpDao().progress("squat:T1")!!
            val corrected = repo.getSession(third)!!.exercises.single().sets.minBy { it.position }
            repo.saveSet(corrected.copy(actualReps = 1))
            val preview = repo.previewHistoricalCorrection(third)
            assertFalse(preview.canApply)
            assertTrue(preview.conflicts.any { it.contains("manually changed") })
            assertFalse(repo.applyHistoricalCorrection(preview))
            assertEquals(afterReset, db.getGzclpDao().progress("squat:T1"))
            assertEquals(1, repo.getSession(third)!!.exercises.single().sets.minBy { it.position }.actualReps)
        } finally { db.close() }
    }

    @Test fun manualOverrideAndStalePreviewCannotRewriteProgression() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(GzclpTemplateExerciseEntity("a1", 0, 0, "squat", "Squat", "T1", "squat:T1")),
                listOf(GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000,
                    incrementGrams = 5_000)))
            val id = repo.startSession(0)
            repo.getSession(id)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            repo.finishSession(id)
            val amrap = repo.getSession(id)!!.exercises.single().sets.maxBy { it.position }
            repo.saveSet(amrap.copy(actualGrams = 62_000))
            val preview = repo.previewHistoricalCorrection(id)
            assertTrue(preview.canApply)
            repo.saveSet(amrap.copy(actualGrams = 64_000))
            assertFalse(repo.applyHistoricalCorrection(preview))
            val current = db.getGzclpDao().progress("squat:T1")!!
            repo.saveProgress(current.copy(recommendedGrams = 80_000))
            val conflict = repo.previewHistoricalCorrection(id)
            assertFalse(conflict.canApply)
            assertTrue(conflict.conflicts.any { it.contains("manually changed") })
            assertEquals(80_000L, db.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
            assertEquals(64_000L, repo.getSession(id)!!.exercises.single().sets.maxBy { it.position }.actualGrams)
        } finally { db.close() }
    }

    @Test fun correctionPreviewsAndAtomicallyUpdatesOnlyAffectedLift() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(), listOf(
                GzclpTemplateExerciseEntity("a1", 0, 0, "squat", "Squat", "T1", "squat:T1"),
                GzclpTemplateExerciseEntity("b1", 1, 0, "squat", "Squat", "T1", "squat:T1")
            ), listOf(
                GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000, incrementGrams = 5_000),
                GzclpProgressEntity("squat:T2", "squat", "T2", recommendedGrams = 40_000, incrementGrams = 2_500)
            ))
            val first = repo.startSession(0)
            repo.getSession(first)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            assertTrue(repo.finishSession(first))
            val second = repo.startSession(1)
            repo.getSession(second)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            assertTrue(repo.finishSession(second))
            assertEquals(70_000L, db.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
            val corrected = repo.getSession(second)!!.exercises.single().sets.maxBy { it.position }
            repo.saveSet(corrected.copy(actualGrams = 67_000))
            val preview = repo.previewHistoricalCorrection(second)
            assertTrue(preview.conflicts.toString(), preview.canApply)
            assertEquals(72_000L, preview.changes.single().newGrams)
            assertEquals(70_000L, db.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
            assertTrue(repo.applyHistoricalCorrection(preview))
            assertFalse(repo.applyHistoricalCorrection(preview))
            assertEquals(72_000L, db.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
            assertEquals(40_000L, db.getGzclpDao().progress("squat:T2")!!.recommendedGrams)
            assertEquals(67_000L, repo.getSession(second)!!.exercises.single().sets.maxBy { it.position }.actualGrams)
        } finally { db.close() }
    }

    @Test fun changedEarlierStageBlocksSilentReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(), listOf(
                GzclpTemplateExerciseEntity("a1", 0, 0, "squat", "Squat", "T1", "squat:T1"),
                GzclpTemplateExerciseEntity("b1", 1, 0, "squat", "Squat", "T1", "squat:T1")
            ), listOf(GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000,
                incrementGrams = 5_000)))
            val first = repo.startSession(0)
            repo.getSession(first)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            repo.finishSession(first)
            val second = repo.startSession(1)
            repo.getSession(second)!!.exercises.single().sets.forEach { repo.saveSet(it.copy(completed = true)) }
            repo.finishSession(second)
            val old = repo.getSession(first)!!.exercises.single().sets.minBy { it.position }
            repo.saveSet(old.copy(actualReps = 2))
            val preview = repo.previewHistoricalCorrection(first)
            assertFalse(preview.canApply)
            assertTrue(preview.conflicts.isNotEmpty())
            assertFalse(repo.applyHistoricalCorrection(preview))
            assertEquals(70_000L, db.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
        } finally { db.close() }
    }
}
