/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** All loads are stored in grams. Program id 1 is the local user's program. */
@Entity(tableName = "gzclp_program")
data class GzclpProgramEntity(
    @PrimaryKey val id: Int = 1,
    val unit: String = "KG",
    val daysPerWeek: Int = 3,
    val nextWorkoutIndex: Int = 0,
    val t1RestSeconds: Int = 180,
    val t2RestSeconds: Int = 120,
    val t3RestSeconds: Int = 90,
    val autoRest: Boolean = true,
    val barGrams: Long = 20_000,
    val plateInventory: String = "20000:2,15000:2,10000:2,5000:2,2500:2,1250:2",
    val warmupTemplate: String = "40:5,60:3,80:2",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "gzclp_template_exercises", indices = [Index("workoutIndex", "position")])
data class GzclpTemplateExerciseEntity(
    @PrimaryKey val id: String,
    val workoutIndex: Int,
    val position: Int,
    val exerciseId: String,
    val exerciseName: String,
    val tier: String,
    val progressionKey: String,
    val enabled: Boolean = true,
    val cue: String = "",
    val restSeconds: Int = 0
)

@Entity(tableName = "gzclp_progress")
data class GzclpProgressEntity(
    @PrimaryKey val progressionKey: String,
    val exerciseId: String,
    val tier: String,
    val stage: String = "FIRST",
    val recommendedGrams: Long,
    val incrementGrams: Long,
    val cycleStartGrams: Long = recommendedGrams,
    val pendingReset: Boolean = false,
    val lastReason: String = "Starting weight",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "gzclp_sessions", indices = [Index("status"), Index("startedAt")])
data class GzclpSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutIndex: Int,
    val status: String = "ACTIVE",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val notes: String = "",
    val restDeadlineMillis: Long = 0,
    val restPausedMillis: Long = 0,
    val progressionApplied: Boolean = false,
    val historicalCorrection: Boolean = false
)

@Entity(tableName = "gzclp_session_exercises", indices = [Index("sessionId"), Index("position")])
data class GzclpSessionExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val templateId: String,
    val progressionKey: String,
    val exerciseId: String,
    val exerciseName: String,
    val tier: String,
    val stage: String,
    val position: Int,
    val recommendedGrams: Long,
    val targetSets: Int,
    val targetReps: Int,
    val notes: String = "",
    val cue: String = "",
    val restSeconds: Int = 0,
    val skipped: Boolean = false,
    val progressionReason: String = "",
    val nextRecommendedGrams: Long? = null,
    val nextStage: String? = null,
    /** Nullable for sessions created before migration 7→8; never infer missing replay inputs. */
    val baselineIncrementGrams: Long? = null,
    val baselineCycleStartGrams: Long? = null,
    val baselinePendingReset: Boolean? = null
)

@Entity(tableName = "gzclp_sets", indices = [Index("sessionExerciseId", "position")])
data class GzclpSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionExerciseId: Long,
    val position: Int,
    val targetReps: Int,
    val actualReps: Int = 0,
    val targetGrams: Long,
    val actualGrams: Long = targetGrams,
    val completed: Boolean = false,
    val warmup: Boolean = false,
    val amrap: Boolean = false,
    val rir: Int? = null
)

data class GzclpExerciseWithSets(
    @Embedded val exercise: GzclpSessionExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionExerciseId")
    val sets: List<GzclpSetEntity>
)

data class GzclpSessionWithDetails(
    @Embedded val session: GzclpSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId", entity = GzclpSessionExerciseEntity::class)
    val exercises: List<GzclpExerciseWithSets>
)
