/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.data

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import org.librefit.db.AppDatabase
import org.librefit.db.entity.TrainingMeasurementDefinitionEntity
import org.librefit.db.entity.TrainingMeasurementEntity

/** Optional first-party and external observations. Nothing here changes GZCLP progression. */
class TrainingMeasurements @Inject constructor(private val database: AppDatabase) {
    private val dao get() = database.getTrainingDataDao()
    private val ledger = TrainingLedger(database)
    val definitions get() = dao.observeDefinitions()
    fun observations(subjectType: String, subjectId: String) = dao.observeMeasurements(subjectType, subjectId)

    suspend fun clearRevisionHistory() = database.withTransaction { dao.clearEvents() }

    suspend fun define(key: String, label: String, valueType: String, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null,
        collectionMode: String = "CONTEXTUAL", description: String = ""): String = database.withTransaction {
        require(key.matches(Regex("[a-z][a-z0-9_]{1,63}"))) { "Use a stable lowercase key" }
        require(label.isNotBlank() && label.length <= 100)
        require(valueType in setOf("NUMBER", "TEXT", "BOOLEAN"))
        require(collectionMode in setOf("AUTOMATIC", "CONTEXTUAL", "PRE_POST", "EXTERNAL"))
        require(unit == null || (unit.isNotBlank() && unit.length <= 32))
        require(description.length <= 500)
        require(minimum == null || minimum.isFinite())
        require(maximum == null || maximum.isFinite())
        require(minimum == null || maximum == null || minimum <= maximum)
        require(valueType == "NUMBER" || (unit == null && minimum == null && maximum == null))
        val id = UUID.randomUUID().toString()
        dao.insertDefinition(TrainingMeasurementDefinitionEntity(id, key, label, valueType, unit,
            minimum, maximum, collectionMode, description, System.currentTimeMillis()))
        ledger.record("MEASUREMENT_DEFINED", "training_measurement_definitions", id, before = null)
        id
    }

    suspend fun ensureExternalDefinition(key: String, label: String, unit: String): String =
        database.withTransaction {
            dao.definitionByKey(key)?.let { return@withTransaction it.id }
            define(key, label, "NUMBER", unit, collectionMode = "EXTERNAL")
        }

    suspend fun record(definitionId: String, subjectType: String, subjectId: String,
        observedAtMillis: Long?, numberValue: Double? = null, textValue: String? = null,
        booleanValue: Boolean? = null, provenance: String = "USER", sourceRecordId: String? = null,
        quality: String = "OBSERVED", notes: String = ""): String = database.withTransaction {
        val definition = dao.definition(definitionId) ?: error("Measurement definition missing")
        require(subjectType in setOf("SESSION", "EXERCISE", "SET", "REP", "DAY", "PERSON"))
        require(subjectId.isNotBlank())
        require(provenance in setOf("USER", "LIBREFIT", "HEALTH_CONNECT", "OTHER_EXTERNAL"))
        require(quality in setOf("OBSERVED", "SELF_REPORTED", "ESTIMATED", "IMPORTED"))
        require(listOfNotNull(numberValue, textValue, booleanValue).size == 1)
        when (definition.valueType) {
            "NUMBER" -> require(numberValue != null && numberValue.isFinite() &&
                (definition.minimum == null || numberValue >= definition.minimum) &&
                (definition.maximum == null || numberValue <= definition.maximum))
            "TEXT" -> require(textValue != null && textValue.length <= 10_000)
            "BOOLEAN" -> require(booleanValue != null)
        }
        require(provenance != "HEALTH_CONNECT" && provenance != "OTHER_EXTERNAL" ||
            !sourceRecordId.isNullOrBlank()) { "External observations need a source record ID" }
        val existing = if (sourceRecordId != null) dao.externalMeasurement(definitionId, provenance,
            sourceRecordId) else null
        val id = existing?.id ?: UUID.randomUUID().toString()
        val proposed = TrainingMeasurementEntity(id, definitionId, subjectType, subjectId,
            observedAtMillis, existing?.recordedAtMillis ?: System.currentTimeMillis(), numberValue,
            textValue, booleanValue, provenance, sourceRecordId, quality, notes)
        if (existing == proposed) return@withTransaction id
        val before = existing?.let { ledger.snapshot("training_measurements", id) }
        if (existing == null) dao.insertMeasurement(proposed)
        else dao.updateMeasurement(proposed.copy(recordedAtMillis = System.currentTimeMillis()))
        ledger.record(if (existing == null) "MEASUREMENT_RECORDED" else "EXTERNAL_MEASUREMENT_REVISED",
            "training_measurements", id, before = before, observedAtMillis = observedAtMillis)
        id
    }

    suspend fun deleteExternalRecord(sourceRecordId: String) = database.withTransaction {
        require(sourceRecordId.isNotBlank())
        dao.externalMeasurements("HEALTH_CONNECT", sourceRecordId).forEach { existing ->
            val before = ledger.snapshot("training_measurements", existing.id)
            dao.deleteMeasurement(existing.id)
            ledger.record("EXTERNAL_RECORD_DELETED", "training_measurements", existing.id,
                before = before, reason = "Health Connect tombstone: $sourceRecordId")
        }
    }

    suspend fun deleteObservation(id: String) = database.withTransaction {
        val before = ledger.snapshot("training_measurements", id)
        dao.deleteMeasurement(id)
        if (before != null) ledger.record("MEASUREMENT_DELETED", "training_measurements", id, before = before)
    }

    suspend fun deleteDefinition(id: String) = database.withTransaction {
        require(dao.definition(id) != null)
        // A definition with observations is part of their interpretation and cannot be removed alone.
        val count = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM training_measurements WHERE definitionId = ?", arrayOf(id))
            .use { it.moveToFirst(); it.getLong(0) }
        require(count == 0L) { "Delete its observations first" }
        val before = ledger.snapshot("training_measurement_definitions", id)
        dao.deleteDefinition(id)
        ledger.record("MEASUREMENT_DEFINITION_DELETED", "training_measurement_definitions", id, before = before)
    }
}
