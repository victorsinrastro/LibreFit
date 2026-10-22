package org.librefit.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.librefit.db.entity.GzclpProgramEntity
import org.librefit.db.entity.GzclpProgressEntity
import org.librefit.db.entity.GzclpTemplateExerciseEntity
import org.librefit.db.repository.GzclpRepository
import org.librefit.ui.screens.gzclp.defaultLifts

@RunWith(AndroidJUnit4::class)
class GzclpRepositoryIntegrationTest {
    @Test fun activeSessionPersistsAndFinishesExactlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = GzclpRepository(db, db.getGzclpDao())
            repo.configure(GzclpProgramEntity(), defaultLifts.map {
                GzclpTemplateExerciseEntity(it.templateId, it.workout, it.position, it.id,
                    it.name, it.tier, it.key)
            }, defaultLifts.distinctBy { it.key }.map {
                GzclpProgressEntity(it.key, it.id, it.tier, recommendedGrams = 60_000,
                    incrementGrams = 2_500)
            })
            val id = repo.startSession(0)
            val reopened = GzclpRepository(db, db.getGzclpDao())
            assertEquals(id, reopened.startSession(0))
            val active = reopened.activeSession.first()
            assertNotNull(active)
            active!!.exercises.flatMap { it.sets }.forEach { reopened.saveSet(it.copy(completed = true)) }
            assertTrue(reopened.finishSession(id))
            assertEquals(false, reopened.finishSession(id))
            assertEquals(1, db.getGzclpDao().program()?.nextWorkoutIndex)
            assertEquals(62_500L, db.getGzclpDao().progress("Barbell_Full_Squat:T1")?.recommendedGrams)
        } finally { db.close() }
    }
}
