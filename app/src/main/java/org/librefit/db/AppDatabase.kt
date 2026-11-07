/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2024-2026. The LibreFit Contributors
 *
 * LibreFit is subject to additional terms covering author attribution and trademark usage;
 * see the ADDITIONAL_TERMS.md and TRADEMARK_POLICY.md files in the project root.
 */

package org.librefit.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.librefit.db.converters.ExerciseDCConverter
import org.librefit.db.converters.LocalDateTimeConverter
import org.librefit.db.converters.WeightConverter
import org.librefit.db.dao.DatasetDao
import org.librefit.db.dao.GzclpDao
import org.librefit.db.dao.TrainingDataDao
import org.librefit.db.dao.MeasurementDao
import org.librefit.db.dao.WorkoutDao
import org.librefit.db.entity.Exercise
import org.librefit.db.entity.GzclpProgramEntity
import org.librefit.db.entity.GzclpTemplateExerciseEntity
import org.librefit.db.entity.GzclpProgressEntity
import org.librefit.db.entity.GzclpSessionEntity
import org.librefit.db.entity.GzclpSessionExerciseEntity
import org.librefit.db.entity.GzclpSetEntity
import org.librefit.db.entity.TrainingEventEntity
import org.librefit.db.entity.TrainingMeasurementDefinitionEntity
import org.librefit.db.entity.TrainingMeasurementEntity
import org.librefit.db.entity.TrainingBlockEntity
import org.librefit.db.entity.TrainingPlanEntity
import org.librefit.db.entity.ExerciseDC
import org.librefit.db.entity.Measurement
import org.librefit.db.entity.Set
import org.librefit.db.entity.Workout

@Database(
    entities = [Workout::class, Exercise::class, Set::class, Measurement::class, ExerciseDC::class,
        GzclpProgramEntity::class, GzclpTemplateExerciseEntity::class, GzclpProgressEntity::class,
        GzclpSessionEntity::class, GzclpSessionExerciseEntity::class, GzclpSetEntity::class,
        TrainingEventEntity::class, TrainingMeasurementDefinitionEntity::class,
        TrainingMeasurementEntity::class, TrainingBlockEntity::class, TrainingPlanEntity::class],
    version = 10,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5)
    ]
)
@TypeConverters(LocalDateTimeConverter::class, ExerciseDCConverter::class, WeightConverter::class)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val NAME = "librefit_database"

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gzclp_sets ADD COLUMN createdAtMillis INTEGER")
                db.execSQL("ALTER TABLE gzclp_sets ADD COLUMN completedAtMillis INTEGER")
                db.execSQL("ALTER TABLE gzclp_sets ADD COLUMN lastEditedAtMillis INTEGER")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN loadMeaning TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS training_events (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, subjectType TEXT NOT NULL, subjectId TEXT NOT NULL, sessionId INTEGER, observedAtMillis INTEGER, recordedAtMillis INTEGER NOT NULL, beforeJson TEXT, afterJson TEXT, reason TEXT, provenance TEXT NOT NULL, timeQuality TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_events_sessionId ON training_events(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_events_subjectType_subjectId ON training_events(subjectType, subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_events_recordedAtMillis ON training_events(recordedAtMillis)")
                db.execSQL("CREATE TABLE IF NOT EXISTS training_measurement_definitions (id TEXT NOT NULL PRIMARY KEY, `key` TEXT NOT NULL, label TEXT NOT NULL, valueType TEXT NOT NULL, unit TEXT, minimum REAL, maximum REAL, collectionMode TEXT NOT NULL, description TEXT NOT NULL, createdAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_training_measurement_definitions_key ON training_measurement_definitions(`key`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS training_measurements (id TEXT NOT NULL PRIMARY KEY, definitionId TEXT NOT NULL, subjectType TEXT NOT NULL, subjectId TEXT NOT NULL, observedAtMillis INTEGER, recordedAtMillis INTEGER NOT NULL, numberValue REAL, textValue TEXT, booleanValue INTEGER, provenance TEXT NOT NULL, sourceRecordId TEXT, quality TEXT NOT NULL, notes TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_measurements_definitionId ON training_measurements(definitionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_measurements_subjectType_subjectId ON training_measurements(subjectType, subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_measurements_observedAtMillis ON training_measurements(observedAtMillis)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_training_measurements_definitionId_provenance_sourceRecordId ON training_measurements(definitionId,provenance,sourceRecordId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS training_blocks (id TEXT NOT NULL PRIMARY KEY, programId INTEGER NOT NULL, startedAtMillis INTEGER, endedAtMillis INTEGER, firstWorkoutIndex INTEGER NOT NULL, rotationJson TEXT NOT NULL, initialProgramJson TEXT, provenance TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_blocks_programId ON training_blocks(programId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_blocks_startedAtMillis ON training_blocks(startedAtMillis)")
                db.execSQL("CREATE TABLE IF NOT EXISTS training_plans (id TEXT NOT NULL PRIMARY KEY, blockId TEXT NOT NULL, workoutIndex INTEGER NOT NULL, createdAtMillis INTEGER, startedAtMillis INTEGER, completedAtMillis INTEGER, sessionId INTEGER, status TEXT NOT NULL, templateSnapshotJson TEXT, progressionSnapshotJson TEXT, provenance TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_plans_blockId ON training_plans(blockId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_plans_sessionId ON training_plans(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_training_plans_status ON training_plans(status)")
                db.execSQL("INSERT INTO training_blocks (id,programId,startedAtMillis,endedAtMillis,firstWorkoutIndex,rotationJson,initialProgramJson,provenance) SELECT 'legacy-block-1',id,createdAt,NULL,nextWorkoutIndex,'[0,1,2,3]',NULL,'LEGACY' FROM gzclp_program")
                db.execSQL("INSERT INTO training_plans (id,blockId,workoutIndex,createdAtMillis,startedAtMillis,completedAtMillis,sessionId,status,templateSnapshotJson,progressionSnapshotJson,provenance) SELECT 'legacy-plan-1','legacy-block-1',p.nextWorkoutIndex,NULL,s.startedAt,NULL,s.id,CASE WHEN s.id IS NULL THEN 'READY' ELSE 'STARTED' END,NULL,NULL,'LEGACY' FROM gzclp_program p LEFT JOIN gzclp_sessions s ON s.id=(SELECT id FROM gzclp_sessions WHERE status='ACTIVE' ORDER BY startedAt DESC LIMIT 1)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN baselineIncrementGrams INTEGER")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN baselineCycleStartGrams INTEGER")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN baselinePendingReset INTEGER")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gzclp_sessions ADD COLUMN activeElapsedMillis INTEGER")
                db.execSQL("ALTER TABLE gzclp_sessions ADD COLUMN activeSinceMillis INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gzclp_program ADD COLUMN autoRest INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE gzclp_program ADD COLUMN barGrams INTEGER NOT NULL DEFAULT 20000")
                db.execSQL("ALTER TABLE gzclp_program ADD COLUMN plateInventory TEXT NOT NULL DEFAULT '20000:2,15000:2,10000:2,5000:2,2500:2,1250:2'")
                db.execSQL("ALTER TABLE gzclp_template_exercises ADD COLUMN cue TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE gzclp_template_exercises ADD COLUMN restSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE gzclp_sessions ADD COLUMN restDeadlineMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE gzclp_sessions ADD COLUMN restPausedMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN cue TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN restSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE gzclp_sets ADD COLUMN rir INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gzclp_program ADD COLUMN warmupTemplate TEXT NOT NULL DEFAULT '40:5,60:3,80:2'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE exercises
                    ADD COLUMN position INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE exercises AS current
                    SET position = (
                        SELECT COUNT(*) - 1
                        FROM exercises AS previous
                        WHERE previous.workoutId = current.workoutId
                          AND previous.id <= current.id
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_exercises_workoutId_position
                    ON exercises(workoutId, position)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_exercises_idExerciseDC
                    ON exercises(idExerciseDC)
                    """.trimIndent()
                )
            }
        }
    }

    abstract fun getWorkoutDao(): WorkoutDao

    abstract fun getMeasurementDao(): MeasurementDao

    abstract fun getDatasetDao(): DatasetDao

    abstract fun getGzclpDao(): GzclpDao
    abstract fun getTrainingDataDao(): TrainingDataDao
}
