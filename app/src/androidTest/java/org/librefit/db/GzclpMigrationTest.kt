package org.librefit.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the exported version 4 schema, then runs the complete non-destructive migration chain. */
@RunWith(AndroidJUnit4::class)
class GzclpMigrationTest {
    @Test fun legacyWorkoutSurvivesGzclpMigration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val name = "gzclp-migration-${System.nanoTime()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("gzclp-v4-schema.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val file = app.getDatabasePath(name)
        file.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        sqlite.execSQL(indices.getJSONObject(j).getString("createSql")
                            .replace("${'$'}{TABLE_NAME}", table))
                    }
                }
                sqlite.execSQL("INSERT INTO workouts (id, routineId, notes, title, state, timeElapsed, created, completed) " +
                    "VALUES (71, 7, 'legacy note', 'Legacy workout', 'COMPLETED', 1200, " +
                    "'2025-01-01T10:00:00', '2025-01-01T10:20:00')")
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.version = 4
            }

            val migrated = Room.databaseBuilder(app, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_5_6,
                    AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
                .build()
            try {
                    migrated.openHelper.writableDatabase.query(
                        "SELECT title, notes FROM workouts WHERE id = 71"
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("Legacy workout", cursor.getString(0))
                        assertEquals("legacy note", cursor.getString(1))
                    }
                    migrated.openHelper.writableDatabase.query(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'gzclp_program'"
                    ).use { cursor -> assertTrue(cursor.moveToFirst()) }
            } finally {
                migrated.close()
            }
        } finally {
            app.deleteDatabase(name)
        }
    }
}
