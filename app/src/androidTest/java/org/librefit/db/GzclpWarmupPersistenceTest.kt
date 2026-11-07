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

    private suspend fun createFixture(
        unit: WeightUnit = WeightUnit.KG,
        recommendedGrams: Long = 60_000,
        incrementGrams: Long = 5_000
    ): PersistenceFixture {
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
        return PersistenceFixture(db, repository, sessionId, exerciseId)
    }

    @Test fun poundWorkingWeightsSurviveRoomSaveAndReload() = runBlocking {
        val lb = WeightUnit.LB
        val fixture = createFixture(
            unit = lb,
            recommendedGrams = GzclpWeights.toGrams(90.0, lb),
            incrementGrams = GzclpWeights.toGrams(5.0, lb)
        )
        try {
            val repo = fixture.repository
            val set = repo.getSession(fixture.sessionId)!!.exercises.single().sets.first { !it.warmup }
            listOf(90.0, 67.5).forEach { pounds ->
                repeat(3) {
                    val current = repo.getSession(fixture.sessionId)!!.exercises.single().sets.first { it.id == set.id }
                    repo.saveSet(current.copy(actualGrams = GzclpWeights.toGrams(pounds, lb)))
                    val recovered = repo.getSession(fixture.sessionId)!!.exercises.single().sets.first { it.id == set.id }
                    assertEquals(pounds, GzclpWeights.fromGrams(recovered.actualGrams, lb), 0.0)
                }
            }
        } finally { fixture.db.close() }
    }

    @Test fun skippingPendingWarmupsLeavesWorkingSetsAndCompletedWarmups() = runBlocking {
        val fixture = createFixture()
        try {
            val repo = fixture.repository
            val exerciseId = fixture.exerciseId
            repo.generateWarmups(exerciseId)
            val before = repo.getSession(fixture.sessionId)!!.exercises.single()
            val logged = before.sets.first { it.warmup }.copy(completed = true)
            repo.saveSet(logged)
            val workingIds = before.sets.filterNot { it.warmup }.map { it.id }
            repo.skipPendingWarmups(exerciseId)
            val resumed = repo.getSession(fixture.sessionId)!!.exercises.single().sets
            assertEquals(listOf(logged), resumed.filter { it.warmup })
            assertEquals(workingIds, resumed.filterNot { it.warmup }.map { it.id })
            assertTrue(resumed.filterNot { it.warmup }.all { !it.completed })
        } finally { fixture.db.close() }
    }

    @Test fun explicitRegenerationKeepsCompletedWarmupsAndWorkingSets() = runBlocking {
        val fixture = createFixture()
        try {
            val repo = fixture.repository
            val exerciseId = fixture.exerciseId
            repo.generateWarmups(exerciseId)
            val original = repo.getSession(fixture.sessionId)!!.exercises.single().sets
            val completed = original.first { it.warmup }.copy(completed = true, actualReps = 8,
                actualGrams = 22_500)
            repo.saveSet(completed)
            val workingIds = original.filterNot { it.warmup }.map { it.id }
            repo.generateWarmups(exerciseId, replacePending = true)
            val regenerated = repo.getSession(fixture.sessionId)!!.exercises.single().sets
            assertEquals(completed, regenerated.first { it.id == completed.id })
            assertEquals(workingIds, regenerated.filterNot { it.warmup }.map { it.id })
            assertTrue(regenerated.filter { it.warmup && !it.completed }.none { it.id in original.map { old -> old.id } })
        } finally { fixture.db.close() }
    }

    @Test fun repeatedGenerationPreservesLoggedAndManualWarmups() = runBlocking {
        val fixture = createFixture()
        try {
            val repo = fixture.repository
            val exerciseId = fixture.exerciseId
            repo.generateWarmups(exerciseId)
            val first = repo.getSession(fixture.sessionId)!!.exercises.single().sets.filter { it.warmup }
            assertTrue(first.isNotEmpty())
            val edited = first.first().copy(completed = true, actualReps = 7, actualGrams = 22_500)
            repo.saveSet(edited)
            repo.generateWarmups(exerciseId)
            val second = repo.getSession(fixture.sessionId)!!.exercises.single().sets.filter { it.warmup }
            assertEquals(first.size, second.size)
            assertEquals(edited, second.first { it.id == edited.id })
            assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())

            // A user-created warm-up must also block replacement of the existing warm-up sequence.
            repo.saveSet(first.last().copy(id = 0, position = -1, actualReps = 3,
                actualGrams = 30_000, targetGrams = 30_000))
            val before = repo.getSession(fixture.sessionId)!!.exercises.single().sets.filter { it.warmup }
            repo.generateWarmups(exerciseId)
            val after = repo.getSession(fixture.sessionId)!!.exercises.single().sets.filter { it.warmup }
            assertEquals(before.toSet(), after.toSet())
        } finally { fixture.db.close() }
    }
}
