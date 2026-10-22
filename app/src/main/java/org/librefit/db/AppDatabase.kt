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
import org.librefit.db.dao.MeasurementDao
import org.librefit.db.dao.WorkoutDao
import org.librefit.db.entity.Exercise
import org.librefit.db.entity.GzclpProgramEntity
import org.librefit.db.entity.GzclpTemplateExerciseEntity
import org.librefit.db.entity.GzclpProgressEntity
import org.librefit.db.entity.GzclpSessionEntity
import org.librefit.db.entity.GzclpSessionExerciseEntity
import org.librefit.db.entity.GzclpSetEntity
import org.librefit.db.entity.ExerciseDC
import org.librefit.db.entity.Measurement
import org.librefit.db.entity.Set
import org.librefit.db.entity.Workout

@Database(
    entities = [Workout::class, Exercise::class, Set::class, Measurement::class, ExerciseDC::class,
        GzclpProgramEntity::class, GzclpTemplateExerciseEntity::class, GzclpProgressEntity::class,
        GzclpSessionEntity::class, GzclpSessionExerciseEntity::class, GzclpSetEntity::class],
    version = 8,
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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN baselineIncrementGrams INTEGER")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN baselineCycleStartGrams INTEGER")
                db.execSQL("ALTER TABLE gzclp_session_exercises ADD COLUMN baselinePendingReset INTEGER")
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
}
