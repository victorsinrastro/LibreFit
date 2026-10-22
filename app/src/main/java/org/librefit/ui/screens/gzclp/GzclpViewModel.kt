/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.ui.screens.gzclp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.librefit.db.entity.*
import org.librefit.db.repository.GzclpRepository
import org.librefit.db.repository.ReplayPreview
import org.librefit.gzclp.data.TrainingBackup
import org.librefit.gzclp.data.GzclpRestNotification
import android.net.Uri
import org.librefit.gzclp.domain.GzclpWeights
import org.librefit.gzclp.domain.WeightUnit

data class DefaultLift(val workout: Int, val position: Int, val id: String, val name: String, val tier: String) {
    val key get() = "$id:$tier"
    val templateId get() = "${workout}_${position}_${key}"
}

val defaultLifts = listOf(
    DefaultLift(0, 0, "Barbell_Full_Squat", "Squat", "T1"),
    DefaultLift(0, 1, "Barbell_Bench_Press_-_Medium_Grip", "Bench Press", "T2"),
    DefaultLift(0, 2, "Wide-Grip_Lat_Pulldown", "Lat Pulldown", "T3"),
    DefaultLift(1, 0, "Standing_Military_Press", "Overhead Press", "T1"),
    DefaultLift(1, 1, "Barbell_Deadlift", "Deadlift", "T2"),
    DefaultLift(1, 2, "One-Arm_Dumbbell_Row", "Dumbbell Row", "T3"),
    DefaultLift(2, 0, "Barbell_Bench_Press_-_Medium_Grip", "Bench Press", "T1"),
    DefaultLift(2, 1, "Barbell_Full_Squat", "Squat", "T2"),
    DefaultLift(2, 2, "Wide-Grip_Lat_Pulldown", "Lat Pulldown", "T3"),
    DefaultLift(3, 0, "Barbell_Deadlift", "Deadlift", "T1"),
    DefaultLift(3, 1, "Standing_Military_Press", "Overhead Press", "T2"),
    DefaultLift(3, 2, "One-Arm_Dumbbell_Row", "Dumbbell Row", "T3"),
)

@HiltViewModel
class GzclpViewModel @Inject constructor(
    private val repository: GzclpRepository,
    private val backup: TrainingBackup,
    private val restNotification: GzclpRestNotification,
    private val onboardingDraftStore: GzclpOnboardingDraftStore
) : ViewModel() {
    private val sessionEditMutex = Mutex()
    val ready = MutableStateFlow(false)
    val onboardingDraft = MutableStateFlow<GzclpOnboardingDraft?>(null)
    init { viewModelScope.launch {
        onboardingDraft.value = onboardingDraftStore.draft.first()
        repository.program.first()
        repository.activeSession.first()?.session?.let { session ->
            if (session.restDeadlineMillis > 0 || session.restPausedMillis > 0)
                restNotification.show(session)
        }
        ready.value = true
    } }
    fun saveOnboardingDraft(value: GzclpOnboardingDraft) = viewModelScope.launch {
        onboardingDraftStore.save(value)
    }
    val program = repository.program.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val templates = repository.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress = repository.progress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val active = repository.activeSession.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val detailedHistory = repository.history.mapLatest { sessions ->
        sessions.filter { it.status == "COMPLETED" }.mapNotNull { repository.getSession(it.id) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedSession = MutableStateFlow<GzclpSessionWithDetails?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val retryLabel = MutableStateFlow<String?>(null)
    private var retryAction: (() -> Unit)? = null
    private fun clearError() { error.value = null; retryLabel.value = null; retryAction = null }
    private fun recoverableError(message: String, label: String, action: () -> Unit) {
        error.value = message
        retryLabel.value = label
        retryAction = action
    }
    private fun validationOrRetry(errorCause: Exception, context: String, label: String,
        action: () -> Unit) {
        if (errorCause is IllegalArgumentException) {
            error.value = "$context: ${errorCause.message.orEmpty()}"
            retryLabel.value = null
            retryAction = null
        } else recoverableError("$context. ${errorCause.message.orEmpty()}", label, action)
    }
    fun dismissError() = clearError()
    fun retryFailedAction() {
        val action = retryAction ?: return
        clearError()
        action()
    }
    val restorePreview = MutableStateFlow<String?>(null)
    val replayPreview = MutableStateFlow<ReplayPreview?>(null)
    private var pendingRestore: String? = null

    fun exportBackup(uri: Uri): kotlinx.coroutines.Job = viewModelScope.launch {
        try { backup.export(uri); clearError() }
        catch (e: Exception) { recoverableError("Backup was not exported. ${e.message.orEmpty()}",
            "Retry export") { exportBackup(uri) } }
    }
    fun exportCsv(uri: Uri): kotlinx.coroutines.Job = viewModelScope.launch {
        try { backup.exportCsv(uri); clearError() }
        catch (e: Exception) { recoverableError("CSV was not exported. ${e.message.orEmpty()}",
            "Retry export") { exportCsv(uri) } }
    }
    fun previewRestore(uri: Uri): kotlinx.coroutines.Job = viewModelScope.launch {
        try {
            val document = backup.read(uri)
            restorePreview.value = backup.preview(document)
            pendingRestore = document
            clearError()
        } catch (e: Exception) { validationOrRetry(e, "Backup could not be opened", "Retry import") {
            previewRestore(uri) } }
    }
    fun cancelRestore() { pendingRestore = null; restorePreview.value = null }
    fun confirmRestore(): kotlinx.coroutines.Job = viewModelScope.launch {
        try {
            backup.restore(pendingRestore ?: return@launch)
            selectedSession.value = null
            cancelRestore()
            clearError()
        } catch (e: Exception) { validationOrRetry(e, "Backup was not restored; existing data is still available",
            "Retry restore") { confirmRestore() } }
    }

    fun configure(unit: WeightUnit, days: Int, loads: Map<String, String>, increments: Map<String, String>) {
        viewModelScope.launch {
            try {
                val unique = defaultLifts.distinctBy { it.key }
                val entities = unique.map { lift ->
                    val load = loads[lift.key]?.toDoubleOrNull()
                        ?: kotlin.error("Enter a starting weight for ${lift.name} ${lift.tier}.")
                    require(load > 0) { "Starting weights must be greater than zero." }
                    val increment = increments[lift.key]?.toDoubleOrNull()
                        ?: if (lift.id == "Barbell_Deadlift" || lift.id == "Barbell_Full_Squat") 5.0 else 2.5
                    require(increment > 0) { "Increments must be greater than zero." }
                    GzclpProgressEntity(
                        progressionKey = lift.key,
                        exerciseId = lift.id,
                        tier = lift.tier,
                        recommendedGrams = GzclpWeights.toGrams(load, unit),
                        incrementGrams = GzclpWeights.toGrams(increment, unit)
                    )
                }
                repository.configure(
                    GzclpProgramEntity(unit = unit.name, daysPerWeek = days,
                        barGrams = if (unit == WeightUnit.KG) 20_000 else GzclpWeights.toGrams(45.0, WeightUnit.LB),
                        plateInventory = if (unit == WeightUnit.KG)
                            "20000:2,15000:2,10000:2,5000:2,2500:2,1250:2"
                        else listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)
                            .joinToString(",") { "${GzclpWeights.toGrams(it, WeightUnit.LB)}:2" }),
                    defaultLifts.map {
                        GzclpTemplateExerciseEntity(
                            id = it.templateId, workoutIndex = it.workout, position = it.position,
                            exerciseId = it.id, exerciseName = it.name, tier = it.tier,
                            progressionKey = it.key
                        )
                    },
                    entities
                )
                onboardingDraftStore.clear()
                error.value = null
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun start(): kotlinx.coroutines.Job = viewModelScope.launch {
        val current = program.value ?: return@launch
        busy.value = true
        try { repository.startSession(current.nextWorkoutIndex); clearError() }
        catch (e: Exception) { recoverableError("Workout could not start. ${e.message.orEmpty()}",
            "Retry start") { start() } }
        finally { busy.value = false }
    }

    fun saveSet(set: GzclpSetEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try {
            sessionEditMutex.withLock {
                repository.saveSet(set)
                selectedSession.value?.let { selected ->
                    selectedSession.value = repository.getSession(selected.session.id)
                }
            }
            clearError()
        } catch (e: Exception) {
            recoverableError("Set was not saved. ${e.message.orEmpty()}", "Retry save") { saveSet(set) }
        }
    }
    fun saveExercise(exercise: GzclpSessionExerciseEntity) = viewModelScope.launch {
        try { sessionEditMutex.withLock { repository.saveExercise(exercise) } }
        catch (e: Exception) { error.value = e.message ?: "Exercise change could not be saved." }
    }
    fun substituteSession(exerciseId: Long, replacementId: String, replacementName: String,
        workingGrams: Long) = viewModelScope.launch {
        try { sessionEditMutex.withLock {
            repository.substituteSessionExercise(exerciseId, replacementId, replacementName, workingGrams)
        }; error.value = null }
        catch (e: Exception) { error.value = e.message ?: "Exercise could not be substituted." }
    }
    fun undoSessionSubstitution(exerciseId: Long) = viewModelScope.launch {
        try { sessionEditMutex.withLock { repository.restoreSessionExercise(exerciseId) }; error.value = null }
        catch (e: Exception) { error.value = e.message ?: "Substitution could not be undone." }
    }
    fun replaceFutureExercise(templateId: String, replacementId: String,
        replacementName: String, startingGrams: Long) = viewModelScope.launch {
        try { repository.replaceTemplateExercise(templateId, replacementId, replacementName, startingGrams);
            error.value = null }
        catch (e: Exception) { error.value = e.message ?: "Program exercise could not be changed." }
    }
    fun saveSession(session: GzclpSessionEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try {
            sessionEditMutex.withLock {
                repository.saveSession(session)
                restNotification.show(session)
            }
            clearError()
        } catch (e: Exception) {
            recoverableError("Workout change was not saved. ${e.message.orEmpty()}", "Retry save") {
                saveSession(session)
            }
        }
    }
    fun addWarmup(exercise: GzclpSessionExerciseEntity) = viewModelScope.launch {
        try {
            sessionEditMutex.withLock {
                repository.saveSet(GzclpSetEntity(
                    sessionExerciseId = exercise.id, position = -1, targetReps = 5,
                    targetGrams = exercise.recommendedGrams, warmup = true
                ))
            }
        } catch (e: Exception) { error.value = e.message ?: "Warm-up could not be added." }
    }

    fun generateWarmups(exercise: GzclpSessionExerciseEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try { sessionEditMutex.withLock { repository.generateWarmups(exercise.id) }; clearError() }
        catch (e: Exception) { recoverableError("Warm-ups were not generated. ${e.message.orEmpty()}",
            "Retry warm-ups") { generateWarmups(exercise) } }
    }

    fun replacePendingWarmups(exercise: GzclpSessionExerciseEntity) = viewModelScope.launch {
        try { sessionEditMutex.withLock { repository.generateWarmups(exercise.id, replacePending = true) } }
        catch (e: Exception) { error.value = e.message }
    }

    fun deleteSet(id: Long) = viewModelScope.launch {
        try { sessionEditMutex.withLock { repository.deleteSet(id) } }
        catch (e: Exception) { error.value = e.message ?: "Set could not be removed." }
    }

    fun moveSet(set: GzclpSetEntity, direction: Int) = viewModelScope.launch {
        sessionEditMutex.withLock {
            val item = active.value?.exercises?.firstOrNull { it.exercise.id == set.sessionExerciseId }
                ?: return@withLock
            val siblings = item.sets.filter { it.warmup == set.warmup &&
                (set.warmup || it.position >= item.exercise.targetSets) }.sortedBy { it.position }
            val index = siblings.indexOfFirst { it.id == set.id }
            val other = siblings.getOrNull(index + direction) ?: return@withLock
            repository.saveSet(set.copy(position = other.position))
            repository.saveSet(other.copy(position = set.position))
        }
    }

    fun addWorkingSet(exercise: GzclpSessionExerciseEntity) = viewModelScope.launch {
        sessionEditMutex.withLock {
            val item = active.value?.exercises?.firstOrNull { it.exercise.id == exercise.id } ?: return@withLock
            val position = (item.sets.filter { !it.warmup }.maxOfOrNull { it.position } ?: -1) + 1
            repository.saveSet(GzclpSetEntity(sessionExerciseId = exercise.id, position = position,
                targetReps = exercise.targetReps, actualReps = exercise.targetReps,
                targetGrams = exercise.recommendedGrams, actualGrams = exercise.recommendedGrams))
        }
    }

    fun finish() = viewModelScope.launch {
        val id = active.value?.session?.id ?: return@launch
        busy.value = true
        try {
            sessionEditMutex.withLock {
                repository.finishSession(id)
                restNotification.cancel(id)
                selectedSession.value = repository.getSession(id)
            }
            error.value = null
        } catch (e: Exception) { error.value = e.message }
        finally { busy.value = false }
    }

    fun abandon() = viewModelScope.launch {
        val id = active.value?.session?.id ?: return@launch
        sessionEditMutex.withLock { repository.abandonSession(id); restNotification.cancel(id) }
    }

    fun openSession(id: Long) = viewModelScope.launch {
        selectedSession.value = repository.getSession(id)
    }
    fun closeSession() { selectedSession.value = null }

    fun previewHistoricalCorrection(id: Long): kotlinx.coroutines.Job = viewModelScope.launch {
        try { replayPreview.value = repository.previewHistoricalCorrection(id); clearError() }
        catch (e: Exception) { recoverableError("Correction impact could not be calculated. ${e.message.orEmpty()}",
            "Retry preview") { previewHistoricalCorrection(id) } }
    }
    fun cancelHistoricalCorrection() { replayPreview.value = null }
    fun applyHistoricalCorrection() = viewModelScope.launch {
        val approved = replayPreview.value ?: return@launch
        try {
            val applied = sessionEditMutex.withLock { repository.applyHistoricalCorrection(approved) }
            if (applied) {
                selectedSession.value = repository.getSession(approved.sessionId)
                replayPreview.value = null
                error.value = null
            } else error.value = "Training changed since the preview. Review the correction again."
        } catch (e: Exception) { error.value = e.message }
    }

    fun updateProgram(program: GzclpProgramEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try { repository.saveProgram(program); clearError() }
        catch (e: Exception) { validationOrRetry(e, "Program settings were not saved",
            "Retry save") { updateProgram(program) } }
    }
    fun updateProgress(entity: GzclpProgressEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try { repository.saveProgress(entity); clearError() }
        catch (e: Exception) { validationOrRetry(e, "Lift progression was not saved",
            "Retry save") { updateProgress(entity) } }
    }
    fun updateTemplate(entity: GzclpTemplateExerciseEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try { repository.saveTemplate(entity); clearError() }
        catch (e: Exception) { validationOrRetry(e, "Workout exercise was not saved",
            "Retry save") { updateTemplate(entity) } }
    }
    fun removeTemplate(entity: GzclpTemplateExerciseEntity): kotlinx.coroutines.Job = viewModelScope.launch {
        try { repository.deleteTemplate(entity.id); clearError() }
        catch (e: Exception) { validationOrRetry(e, "Workout exercise was not removed",
            "Retry remove") { removeTemplate(entity) } }
    }
    fun confirmT1Reset(key: String, fiveRm: Long): kotlinx.coroutines.Job = viewModelScope.launch {
        try { repository.confirmT1Reset(key, fiveRm); clearError() }
        catch (e: Exception) { validationOrRetry(e, "T1 reset was not saved",
            "Retry reset") { confirmT1Reset(key, fiveRm) } }
    }
    fun confirmT2Reset(key: String, load: Long): kotlinx.coroutines.Job = viewModelScope.launch {
        try { repository.confirmT2Reset(key, load); clearError() }
        catch (e: Exception) { validationOrRetry(e, "T2 reset was not saved",
            "Retry reset") { confirmT2Reset(key, load) } }
    }
}
