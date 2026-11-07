/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A period of training. Config changes within a block are preserved by the event ledger. */
@Entity(tableName = "training_blocks", indices = [Index("programId"), Index("startedAtMillis")])
data class TrainingBlockEntity(
    @PrimaryKey val id: String,
    val programId: Int,
    val startedAtMillis: Long?,
    val endedAtMillis: Long? = null,
    val firstWorkoutIndex: Int,
    val rotationJson: String,
    val initialProgramJson: String?,
    val provenance: String = "LIBREFIT"
)

/** A dated snapshot of what was expected next; actual prescriptions live in session exercises. */
@Entity(tableName = "training_plans", indices = [Index("blockId"), Index("sessionId"), Index("status")])
data class TrainingPlanEntity(
    @PrimaryKey val id: String,
    val blockId: String,
    val workoutIndex: Int,
    val createdAtMillis: Long?,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val sessionId: Long? = null,
    val status: String = "READY",
    val templateSnapshotJson: String? = null,
    val progressionSnapshotJson: String? = null,
    val provenance: String = "LIBREFIT"
)

/** A durable revision of first-party state. JSON values are snapshots, never derived estimates. */
@Entity(tableName = "training_events", indices = [Index("sessionId"), Index("subjectType", "subjectId"), Index("recordedAtMillis")])
data class TrainingEventEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val subjectType: String,
    val subjectId: String,
    val sessionId: Long? = null,
    /** Time of the user action; null when the event was imported without a trustworthy time. */
    val observedAtMillis: Long? = null,
    val recordedAtMillis: Long,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val reason: String? = null,
    val provenance: String = "LIBREFIT",
    val timeQuality: String = "OBSERVED"
)

/** User-defined fields are typed and validated independently of any external health schema. */
@Entity(tableName = "training_measurement_definitions", indices = [Index(value = ["key"], unique = true)])
data class TrainingMeasurementDefinitionEntity(
    @PrimaryKey val id: String,
    val key: String,
    val label: String,
    val valueType: String,
    val unit: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val collectionMode: String = "CONTEXTUAL",
    val description: String = "",
    val createdAtMillis: Long
)

@Entity(tableName = "training_measurements", indices = [Index("definitionId"),
    Index("subjectType", "subjectId"), Index("observedAtMillis"),
    Index(value = ["definitionId", "provenance", "sourceRecordId"], unique = true)])
data class TrainingMeasurementEntity(
    @PrimaryKey val id: String,
    val definitionId: String,
    val subjectType: String,
    val subjectId: String,
    val observedAtMillis: Long?,
    val recordedAtMillis: Long,
    val numberValue: Double? = null,
    val textValue: String? = null,
    val booleanValue: Boolean? = null,
    val provenance: String = "USER",
    val sourceRecordId: String? = null,
    val quality: String = "OBSERVED",
    val notes: String = ""
)
