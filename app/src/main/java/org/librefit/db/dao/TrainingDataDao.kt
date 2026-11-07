/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.librefit.db.entity.TrainingEventEntity
import org.librefit.db.entity.TrainingMeasurementDefinitionEntity
import org.librefit.db.entity.TrainingMeasurementEntity
import org.librefit.db.entity.TrainingBlockEntity
import org.librefit.db.entity.TrainingPlanEntity

@Dao
interface TrainingDataDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBlock(value: TrainingBlockEntity)

    @Query("SELECT * FROM training_blocks ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun currentBlock(): TrainingBlockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(value: TrainingPlanEntity)

    @Query("SELECT * FROM training_plans WHERE status = 'READY' ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun readyPlan(): TrainingPlanEntity?

    @Query("SELECT * FROM training_plans WHERE sessionId = :sessionId LIMIT 1")
    suspend fun planForSession(sessionId: Long): TrainingPlanEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(value: TrainingEventEntity): Long

    @Query("SELECT * FROM training_events WHERE sessionId = :sessionId ORDER BY recordedAtMillis, id")
    suspend fun eventsForSession(sessionId: Long): List<TrainingEventEntity>

    @Query("SELECT * FROM training_events ORDER BY recordedAtMillis, id")
    suspend fun allEvents(): List<TrainingEventEntity>

    @Query("DELETE FROM training_events")
    suspend fun clearEvents()

    @Query("DELETE FROM training_events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDefinition(value: TrainingMeasurementDefinitionEntity)

    @Query("SELECT * FROM training_measurement_definitions WHERE id = :id")
    suspend fun definition(id: String): TrainingMeasurementDefinitionEntity?

    @Query("SELECT * FROM training_measurement_definitions WHERE `key` = :key LIMIT 1")
    suspend fun definitionByKey(key: String): TrainingMeasurementDefinitionEntity?

    @Query("SELECT * FROM training_measurement_definitions ORDER BY key")
    suspend fun definitions(): List<TrainingMeasurementDefinitionEntity>

    @Query("SELECT * FROM training_measurement_definitions ORDER BY key")
    fun observeDefinitions(): Flow<List<TrainingMeasurementDefinitionEntity>>

    @Query("SELECT * FROM training_measurements WHERE subjectType = :subjectType AND subjectId = :subjectId ORDER BY recordedAtMillis DESC")
    fun observeMeasurements(subjectType: String, subjectId: String): Flow<List<TrainingMeasurementEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeasurement(value: TrainingMeasurementEntity)

    @Update
    suspend fun updateMeasurement(value: TrainingMeasurementEntity)

    @Query("SELECT * FROM training_measurements WHERE definitionId = :definitionId AND provenance = :provenance AND sourceRecordId = :sourceRecordId LIMIT 1")
    suspend fun externalMeasurement(definitionId: String, provenance: String,
        sourceRecordId: String): TrainingMeasurementEntity?

    @Query("SELECT * FROM training_measurements WHERE provenance = :provenance AND sourceRecordId = :sourceRecordId")
    suspend fun externalMeasurements(provenance: String, sourceRecordId: String): List<TrainingMeasurementEntity>

    @Query("SELECT * FROM training_measurements WHERE id = :id")
    suspend fun measurement(id: String): TrainingMeasurementEntity?

    @Query("DELETE FROM training_measurements WHERE id = :id")
    suspend fun deleteMeasurement(id: String)

    @Query("DELETE FROM training_measurement_definitions WHERE id = :id")
    suspend fun deleteDefinition(id: String)
}
