/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.librefit.db.entity.*

@Dao
interface GzclpDao {
    @Query("SELECT * FROM gzclp_program WHERE id = 1")
    fun observeProgram(): Flow<GzclpProgramEntity?>

    @Query("SELECT * FROM gzclp_program WHERE id = 1")
    suspend fun program(): GzclpProgramEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgram(value: GzclpProgramEntity)

    @Query("SELECT * FROM gzclp_template_exercises ORDER BY workoutIndex, position")
    fun observeTemplates(): Flow<List<GzclpTemplateExerciseEntity>>

    @Query("SELECT * FROM gzclp_template_exercises WHERE workoutIndex = :index AND enabled = 1 ORDER BY position")
    suspend fun templatesForWorkout(index: Int): List<GzclpTemplateExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplates(values: List<GzclpTemplateExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(value: GzclpTemplateExerciseEntity)

    @Query("DELETE FROM gzclp_template_exercises WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("SELECT * FROM gzclp_progress ORDER BY tier, exerciseId")
    fun observeProgress(): Flow<List<GzclpProgressEntity>>

    @Query("SELECT * FROM gzclp_progress WHERE progressionKey = :key")
    suspend fun progress(key: String): GzclpProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(values: List<GzclpProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(value: GzclpProgressEntity)

    @Query("SELECT * FROM gzclp_sessions WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): GzclpSessionEntity?

    @Transaction
    @Query("SELECT * FROM gzclp_sessions WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<GzclpSessionWithDetails?>

    @Query("SELECT * FROM gzclp_sessions WHERE status = 'COMPLETED' ORDER BY finishedAt DESC")
    fun observeHistory(): Flow<List<GzclpSessionEntity>>

    @Query("SELECT * FROM gzclp_sessions WHERE status = 'COMPLETED' ORDER BY finishedAt, id")
    suspend fun completedSessionsAsc(): List<GzclpSessionEntity>

    @Transaction
    @Query("SELECT * FROM gzclp_sessions WHERE id = :id")
    suspend fun session(id: Long): GzclpSessionWithDetails?

    @Insert
    suspend fun insertSession(value: GzclpSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(value: GzclpSessionEntity)

    @Insert
    suspend fun insertExercise(value: GzclpSessionExerciseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExercise(value: GzclpSessionExerciseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSet(value: GzclpSetEntity)

    @Query("DELETE FROM gzclp_sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("SELECT s.* FROM gzclp_sessions s INNER JOIN gzclp_session_exercises e ON e.sessionId = s.id WHERE e.id = :exerciseId")
    suspend fun sessionForExercise(exerciseId: Long): GzclpSessionEntity?

    @Query("SELECT s.* FROM gzclp_sessions s INNER JOIN gzclp_session_exercises e ON e.sessionId = s.id INNER JOIN gzclp_sets gs ON gs.sessionExerciseId = e.id WHERE gs.id = :setId")
    suspend fun sessionForSet(setId: Long): GzclpSessionEntity?
}
