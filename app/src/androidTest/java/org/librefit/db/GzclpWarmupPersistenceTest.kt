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
import org.librefit.gzclp.domain.GzclpWeights
import org.librefit.gzclp.domain.WeightUnit

@RunWith(AndroidJUnit4::class)
class GzclpWarmupPersistenceTest {
    private data class PersistenceFixture(
        val db: AppDatabase,
        val repository: GzclpRepository,
        val sessionId: Long,
        val exerciseId: Long
    )

    private fun withFixture(
        unit: WeightUnit = WeightUnit.KG,
        recommendedGrams: Long = 60_000,
        incrementGrams: Long = 5_000,
        block: suspend (PersistenceFixture) -> Unit
    ) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = GzclpRepository(db, db.getGzclpDao())
        repository.configure(
            GzclpProgramEntity(unit = unit.name),
            listOf(GzclpTemplateExerciseEntity("a1-squat", 0, 0, "squat", "Squat", "T1", "squat:T1")),
            listOf(GzclpProgressEntity(
                "squat:T1", "squat", "T1",
                recommendedGrams = recommendedGrams,
                incrementGrams = incrementGrams
            ))
        )
        val sessionId = repository.startSession(0)
        val exerciseId = repository.getSession(sessionId)!!.exercises.single().exercise.id
        val fixture = PersistenceFixture(db, repository, sessionId, exerciseId)
        try { block(fixture) } finally { db.close() }
    }

    private suspend fun PersistenceFixture.exercise() =
        repository.getSession(sessionId)!!.exercises.single()

    @Test fun threeOfFiveThenSkipTwoPreservesT2AndProgression() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(),
                listOf(
                    GzclpTemplateExerciseEntity("a1-squat", 0, 0, "squat", "Squat", "T1", "squat:T1"),
                    GzclpTemplateExerciseEntity("a1-bench", 0, 1, "bench", "Bench", "T2", "bench:T2")),
                listOf(
                    GzclpProgressEntity("squat:T1", "squat", "T1", recommendedGrams = 60_000, incrementGrams = 5_000),
                    GzclpProgressEntity("bench:T2", "bench", "T2", recommendedGrams = 40_000, incrementGrams = 2_500)))
            val sessionId = repo.startSession(0)
            val initial = repo.getSession(sessionId)!!.exercises
            val squat = initial.first { it.exercise.exerciseId == "squat" }
            val bench = initial.first { it.exercise.exerciseId == "bench" }
            squat.sets.filterNot { it.warmup }.take(3).forEach { repo.saveSet(it.copy(completed = true, actualReps = 3)) }
            repo.skipPendingWorkingSets(squat.exercise.id)
            val resumed = repo.getSession(sessionId)!!.exercises
            assertEquals(3, resumed.first { it.exercise.id == squat.exercise.id }.sets.count { !it.warmup && it.completed })
            assertEquals(0, resumed.first { it.exercise.id == squat.exercise.id }.sets.count { !it.warmup && !it.completed })
            assertEquals(bench.sets.map { it.id }, resumed.first { it.exercise.id == bench.exercise.id }.sets.map { it.id })
            repo.finishSession(sessionId)
            val finished = repo.getSession(sessionId)!!.exercises.first { it.exercise.id == squat.exercise.id }
            assertTrue(finished.exercise.progressionReason.contains("Partial exercise"))
            assertEquals(60_000, db.getGzclpDao().progress("squat:T1")!!.recommendedGrams)
        } finally { db.close() }
    }

    @Test fun poundWorkingWeightsSurviveRoomSaveAndReload() = withFixture(
        unit = WeightUnit.LB,
        recommendedGrams = GzclpWeights.toGrams(90.0, WeightUnit.LB),
        incrementGrams = GzclpWeights.toGrams(5.0, WeightUnit.LB)
    ) { fixture ->
        val lb = WeightUnit.LB
        val repo = fixture.repository
        val set = fixture.exercise().sets.first { !it.warmup }
        listOf(90.0, 67.5).forEach { pounds ->
            repeat(3) {
                val current = fixture.exercise().sets.first { it.id == set.id }
                repo.saveSet(current.copy(actualGrams = GzclpWeights.toGrams(pounds, lb)))
                val recovered = fixture.exercise().sets.first { it.id == set.id }
                assertEquals(pounds, GzclpWeights.fromGrams(recovered.actualGrams, lb), 0.0)
            }
        }
    }

    @Test fun skippingPendingWarmupsLeavesWorkingSetsAndCompletedWarmups() = withFixture { fixture ->
        val repo = fixture.repository
        val exerciseId = fixture.exerciseId
        repo.generateWarmups(exerciseId)
        val before = fixture.exercise()
        val logged = before.sets.first { it.warmup }.copy(completed = true)
        repo.saveSet(logged)
        val workingIds = before.sets.filterNot { it.warmup }.map { it.id }
        repo.skipPendingWarmups(exerciseId)
        val resumed = fixture.exercise().sets
        assertEquals(listOf(logged), resumed.filter { it.warmup })
        assertEquals(workingIds, resumed.filterNot { it.warmup }.map { it.id })
        assertTrue(resumed.filterNot { it.warmup }.all { !it.completed })
    }

    @Test fun explicitRegenerationKeepsCompletedWarmupsAndWorkingSets() = withFixture { fixture ->
        val repo = fixture.repository
        val exerciseId = fixture.exerciseId
        repo.generateWarmups(exerciseId)
        val original = fixture.exercise().sets
        val completed = original.first { it.warmup }.copy(completed = true, actualReps = 8,
            actualGrams = 22_500)
        repo.saveSet(completed)
        val workingIds = original.filterNot { it.warmup }.map { it.id }
        repo.generateWarmups(exerciseId, replacePending = true)
        val regenerated = fixture.exercise().sets
        assertEquals(completed, regenerated.first { it.id == completed.id })
        assertEquals(workingIds, regenerated.filterNot { it.warmup }.map { it.id })
        assertTrue(regenerated.filter { it.warmup && !it.completed }.none { it.id in original.map { old -> old.id } })
    }

    @Test fun repeatedGenerationPreservesLoggedAndManualWarmups() = withFixture { fixture ->
        val repo = fixture.repository
        val exerciseId = fixture.exerciseId
        repo.generateWarmups(exerciseId)
        val first = fixture.exercise().sets.filter { it.warmup }
        assertTrue(first.isNotEmpty())
        val edited = first.first().copy(completed = true, actualReps = 7, actualGrams = 22_500)
        repo.saveSet(edited)
        repo.generateWarmups(exerciseId)
        val second = fixture.exercise().sets.filter { it.warmup }
        assertEquals(first.size, second.size)
        assertEquals(edited, second.first { it.id == edited.id })
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())

        // A user-created warm-up must also block replacement of the existing warm-up sequence.
        repo.saveSet(first.last().copy(id = 0, position = -1, actualReps = 3,
            actualGrams = 30_000, targetGrams = 30_000))
        val before = fixture.exercise().sets.filter { it.warmup }
        repo.generateWarmups(exerciseId)
        val after = fixture.exercise().sets.filter { it.warmup }
        assertEquals(before.toSet(), after.toSet())
    }
}
