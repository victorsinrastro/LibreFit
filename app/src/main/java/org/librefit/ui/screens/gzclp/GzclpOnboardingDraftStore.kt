/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.ui.screens.gzclp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Persists unfinished setup separately from the configured training program. */
data class GzclpOnboardingDraft(
    val unit: String = "KG",
    val days: Int = 3,
    val loads: Map<String, String> = emptyMap(),
    val increments: Map<String, String> = emptyMap(),
    val fiveRm: Boolean = false,
    val advanced: Boolean = false,
    val step: Int = 0
)

@Singleton
class GzclpOnboardingDraftStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("gzclp_onboarding_draft_v1")
    val draft: Flow<GzclpOnboardingDraft> = dataStore.data.map { preferences ->
        runCatching {
            val raw = preferences[key] ?: return@map GzclpOnboardingDraft()
            val json = JSONObject(raw)
            fun readMap(name: String): Map<String, String> {
                val values = json.optJSONObject(name) ?: return emptyMap()
                return values.keys().asSequence().associateWith { values.optString(it) }
            }
            GzclpOnboardingDraft(
                unit = json.optString("unit", "KG"), days = json.optInt("days", 3),
                loads = readMap("loads"), increments = readMap("increments"),
                fiveRm = json.optBoolean("fiveRm"), advanced = json.optBoolean("advanced"),
                step = json.optInt("step").coerceIn(0, 3)
            )
        }.getOrDefault(GzclpOnboardingDraft())
    }

    suspend fun save(value: GzclpOnboardingDraft) {
        val json = JSONObject().put("unit", value.unit).put("days", value.days)
            .put("loads", JSONObject(value.loads)).put("increments", JSONObject(value.increments))
            .put("fiveRm", value.fiveRm).put("advanced", value.advanced).put("step", value.step)
        dataStore.edit { it[key] = json.toString() }
    }

    suspend fun clear() { dataStore.edit { it.remove(key) } }
}
