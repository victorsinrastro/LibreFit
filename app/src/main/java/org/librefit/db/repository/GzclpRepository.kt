/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db.repository

import androidx.room.withTransaction
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import org.librefit.db.AppDatabase
import org.librefit.db.dao.GzclpDao
import org.librefit.db.entity.*
import org.librefit.gzclp.domain.CompletedSet
import org.librefit.gzclp.domain.GzclpEngine
import org.librefit.gzclp.domain.WeightUnit
import org.librefit.gzclp.domain.LiftProgress
import org.librefit.gzclp.domain.Stage
import org.librefit.gzclp.domain.Tier
import org.librefit.gzclp.domain.WarmupGenerator
import org.librefit.gzclp.domain.Plate
import org.librefit.gzclp.domain.RestDeadline
import org.librefit.gzclp.domain.SessionClock
import org.librefit.gzclp.domain.ExerciseAlternatives
import org.librefit.gzclp.data.TrainingLedger
import javax.inject.Inject
import javax.inject.Singleton

data class ReplayChange(
    val progressionKey: String,
    val exerciseName: String,
    val tier: String,
    val oldGrams: Long,
    val newGrams: Long,
    val oldStage: String,
    val newStage: String,
    val reason: String
)

data class ReplayPreview(val sessionId: Long, val changes: List<ReplayChange>,
    val conflicts: List<String>, val sourceFingerprint: Int = 0) {
    val canApply: Boolean get() = conflicts.isEmpty()
}

/** Single persisted source of truth for GZCLP, independent of generic LibreFit routines. */
@Singleton
class GzclpRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: GzclpDao
) {
    private val ledger = TrainingLedger(database)
    private val trainingData get() = database.getTrainingDataDao()
    private fun loadMeaning(exerciseId: String): String? = when (ExerciseAlternatives.equipmentFor(exerciseId)) {
        "Barbell" -> "TOTAL_BARBELL"
        "Dumbbell" -> "PER_DUMBBELL"
        "Machine" -> "MACHINE_DISPLAY"
        "Cable" -> "CABLE_STACK"
        "Bodyweight" -> "BODYWEIGHT"
        else -> null
    }

    private suspend fun createReadyPlan(workoutIndex: Int): TrainingPlanEntity {
        val block = trainingData.currentBlock() ?: error("Training block missing")
        val templates = dao.templatesForWorkout(workoutIndex)
        val templateSnapshots = JSONArray()
        val progressionSnapshots = JSONArray()
        templates.forEach { template ->
            ledger.snapshot("gzclp_template_exercises", template.id)?.let { templateSnapshots.put(JSONObject(it)) }
            ledger.snapshot("gzclp_progress", template.progressionKey)?.let { progressionSnapshots.put(JSONObject(it)) }
        }
        val plan = TrainingPlanEntity(UUID.randomUUID().toString(), block.id, workoutIndex,
            System.currentTimeMillis(), templateSnapshotJson = templateSnapshots.toString(),
            progressionSnapshotJson = progressionSnapshots.toString())
        trainingData.upsertPlan(plan)
        ledger.record("SESSION_PLANNED", "training_plans", plan.id, before = null)
        return plan
    }
    val program: Flow<GzclpProgramEntity?> = dao.observeProgram()
    val templates: Flow<List<GzclpTemplateExerciseEntity>> = dao.observeTemplates()
    val progress: Flow<List<GzclpProgressEntity>> = dao.observeProgress()
    val activeSession: Flow<GzclpSessionWithDetails?> = dao.observeActive()
    val history: Flow<List<GzclpSessionEntity>> = dao.observeHistory()

    suspend fun noteAppVisibility(visible: Boolean) = database.withTransaction {
        val session = dao.active() ?: return@withTransaction
        ledger.note(if (visible) "APP_FOREGROUNDED" else "APP_BACKGROUNDED",
            "gzclp_sessions", session.id.toString(), session.id)
    }

    suspend fun configure(
        program: GzclpProgramEntity,
        templates: List<GzclpTemplateExerciseEntity>,
        progress: List<GzclpProgressEntity>
    ) = database.withTransaction {
        require(dao.program() == null) { "Program is already configured; use settings methods." }
        require(program.daysPerWeek == 3 || program.daysPerWeek == 4)
        dao.upsertProgram(program.copy(id = 1, nextWorkoutIndex = 0))
        ledger.record("PROGRAM_CONFIGURED", "gzclp_program", "1", before = null)
        dao.upsertTemplates(templates)
        dao.upsertProgress(progress)
        templates.forEach { ledger.record("TEMPLATE_CREATED", "gzclp_template_exercises", it.id, before = null) }
        progress.forEach { ledger.record("PROGRESSION_INITIALIZED", "gzclp_progress", it.progressionKey, before = null) }
        val block = TrainingBlockEntity(UUID.randomUUID().toString(), 1, System.currentTimeMillis(),
            firstWorkoutIndex = 0, rotationJson = "[0,1,2,3]",
            initialProgramJson = ledger.snapshot("gzclp_program", "1"))
        trainingData.insertBlock(block)
        ledger.record("BLOCK_STARTED", "training_blocks", block.id, before = null)
        createReadyPlan(0)
    }

    suspend fun saveProgram(value: GzclpProgramEntity) = database.withTransaction {
        require(value.daysPerWeek == 3 || value.daysPerWeek == 4)
        val current = dao.program() ?: error("Configure GZCLP first")
        val before = ledger.snapshot("gzclp_program", "1")
        dao.upsertProgram(value.copy(id = 1, nextWorkoutIndex = current.nextWorkoutIndex))
        ledger.record("PROGRAM_CHANGED", "gzclp_program", "1", before = before)
    }

    suspend fun saveTemplate(value: GzclpTemplateExerciseEntity) = database.withTransaction {
        val before = ledger.snapshot("gzclp_template_exercises", value.id)
        dao.upsertTemplate(value)
        ledger.record("TEMPLATE_CHANGED", "gzclp_template_exercises", value.id, before = before)
    }
    suspend fun deleteTemplate(id: String) = database.withTransaction {
        val before = ledger.snapshot("gzclp_template_exercises", id)
        dao.deleteTemplate(id)
        ledger.record("TEMPLATE_DELETED", "gzclp_template_exercises", id, before = before)
    }
    suspend fun saveProgress(value: GzclpProgressEntity) = database.withTransaction {
        require(value.recommendedGrams > 0 && value.incrementGrams > 0)
        val previous = dao.progress(value.progressionKey)
        if (previous != null && previous.copy(lastReason = value.lastReason,
            updatedAt = value.updatedAt) == value) return@withTransaction
        val before = ledger.snapshot("gzclp_progress", value.progressionKey)
        val reason = when {
            previous == null -> value.lastReason
            previous.stage != value.stage -> "Manual stage override"
            previous.recommendedGrams != value.recommendedGrams -> "Manual working weight override"
            previous.incrementGrams != value.incrementGrams -> "Manual increment change"
            else -> previous.lastReason
        }
        dao.upsertProgress(value.copy(lastReason = reason, updatedAt = System.currentTimeMillis()))
        ledger.record("PROGRESSION_OVERRIDE", "gzclp_progress", value.progressionKey,
            before = before, reason = reason)
    }
    suspend fun getSession(id: Long): GzclpSessionWithDetails? = dao.session(id)

    /** A session replacement has no program progression. Recorded sets cannot be relabeled. */
    suspend fun substituteSessionExercise(exerciseId: Long, replacementId: String,
        replacementName: String, workingGrams: Long) = database.withTransaction {
        require(replacementId.isNotBlank() && replacementName.isNotBlank() && workingGrams > 0)
        val (owner, item) = sessionExercise(exerciseId, "Workout missing")
        require(owner.status == "ACTIVE") { "Only an active workout can be changed" }
        require(item.sets.none { it.completed }) { "Recorded sets must stay with their original exercise" }
        require(item.sets.none { it.warmup }) { "Remove pending warm-ups before substituting" }
        val current = item.exercise
        val template = dao.templatesForWorkout(owner.workoutIndex).firstOrNull {
            it.id == current.templateId } ?: error("Original program exercise missing")
        require(template.exerciseId == current.exerciseId &&
            template.progressionKey == current.progressionKey) {
            "Undo the future program change before replacing today's lift"
        }
        val before = ledger.snapshot("gzclp_session_exercises", exerciseId.toString())
        dao.upsertExercise(current.copy(exerciseId = replacementId, exerciseName = replacementName,
            progressionKey = "session:$exerciseId:$replacementId:${current.tier}",
            recommendedGrams = workingGrams, loadMeaning = loadMeaning(replacementId)))
        item.sets.forEach {
            val setBefore = ledger.snapshot("gzclp_sets", it.id.toString())
            dao.upsertSet(it.copy(targetGrams = workingGrams, actualGrams = workingGrams))
            ledger.record("SUBSTITUTED_SET_PRESCRIPTION", "gzclp_sets", it.id.toString(),
                owner.id, setBefore)
        }
        ledger.record("SESSION_SUBSTITUTION", "gzclp_session_exercises", exerciseId.toString(),
            owner.id, before, "Original lift progression is unchanged")
    }

    suspend fun restoreSessionExercise(exerciseId: Long) = database.withTransaction {
        val (owner, item) = sessionExercise(exerciseId, "Workout missing")
        require(owner.status == "ACTIVE")
        require(item.exercise.progressionKey.startsWith("session:")) { "No session replacement to undo" }
        require(item.sets.none { it.completed }) { "Recorded sets must stay with their replacement exercise" }
        val template = dao.templatesForWorkout(owner.workoutIndex).firstOrNull {
            it.id == item.exercise.templateId } ?: error("Original program exercise missing")
        val original = dao.progress(template.progressionKey) ?: error("Original progression missing")
        val before = ledger.snapshot("gzclp_session_exercises", exerciseId.toString())
        dao.upsertExercise(item.exercise.copy(exerciseId = template.exerciseId,
            exerciseName = template.exerciseName, progressionKey = template.progressionKey,
            recommendedGrams = original.recommendedGrams, loadMeaning = loadMeaning(template.exerciseId)))
        item.sets.forEach {
            val setBefore = ledger.snapshot("gzclp_sets", it.id.toString())
            dao.upsertSet(it.copy(targetGrams = original.recommendedGrams,
                actualGrams = original.recommendedGrams))
            ledger.record("SUBSTITUTION_UNDONE_SET", "gzclp_sets", it.id.toString(), owner.id, setBefore)
        }
        ledger.record("SESSION_SUBSTITUTION_UNDONE", "gzclp_session_exercises", exerciseId.toString(),
            owner.id, before)
    }

    /** Changes future workouts only; the currently active prescription remains historical truth. */
    suspend fun replaceTemplateExercise(templateId: String, replacementId: String,
        replacementName: String, startingGrams: Long) = database.withTransaction {
        require(replacementId.isNotBlank() && replacementName.isNotBlank() && startingGrams > 0)
        val active = dao.active()?.let { dao.session(it.id) }
        require(active?.exercises?.none { it.exercise.templateId == templateId &&
            it.exercise.progressionKey.startsWith("session:") } != false) {
            "Undo today's temporary substitution before changing the program"
        }
        var template: GzclpTemplateExerciseEntity? = null
        for (day in 0..3) {
            template = dao.templatesForWorkout(day).firstOrNull { it.id == templateId }
            if (template != null) break
        }
        val existing = template ?: error("Program exercise missing")
        val key = "$replacementId:${existing.tier}"
        if (dao.progress(key) == null) {
            dao.upsertProgress(GzclpProgressEntity(
                progressionKey = key, exerciseId = replacementId, tier = existing.tier,
                recommendedGrams = startingGrams, incrementGrams = 2_500))
            ledger.record("PROGRESSION_INITIALIZED", "gzclp_progress", key, before = null,
                reason = "New independent exercise")
        }
        val before = ledger.snapshot("gzclp_template_exercises", templateId)
        dao.upsertTemplate(existing.copy(exerciseId = replacementId,
            exerciseName = replacementName, progressionKey = key))
        ledger.record("PROGRAM_SUBSTITUTION", "gzclp_template_exercises", templateId,
            before = before, reason = "Replacement has independent progression")
    }

    /** Replays only the affected exercise/tier keys. Actual sets are never rewritten. */
    suspend fun previewHistoricalCorrection(sessionId: Long): ReplayPreview = database.withTransaction {
        replayPlan(sessionId).first
    }

    /** Recomputes the preview inside the write transaction so a stale approval cannot be applied. */
    suspend fun applyHistoricalCorrection(approved: ReplayPreview): Boolean = database.withTransaction {
        val (fresh, updates, historicalDecisions) = replayPlan(approved.sessionId)
        if (fresh != approved || !fresh.canApply) return@withTransaction false
        updates.forEach { (key, state) ->
            val current = dao.progress(key) ?: error("Progression disappeared")
            val before = ledger.snapshot("gzclp_progress", key)
            dao.upsertProgress(current.copy(stage = state.stage.name,
                recommendedGrams = state.recommendedGrams, incrementGrams = state.incrementGrams,
                cycleStartGrams = state.cycleStartGrams, pendingReset = state.pendingReset,
                lastReason = fresh.changes.find { it.progressionKey == key }?.reason ?: current.lastReason,
                updatedAt = System.currentTimeMillis()))
            ledger.record("HISTORICAL_REPLAY", "gzclp_progress", key, approved.sessionId,
                before, fresh.changes.find { it.progressionKey == key }?.reason)
        }
        historicalDecisions.forEach {
            val before = ledger.snapshot("gzclp_session_exercises", it.id.toString())
            dao.upsertExercise(it)
            ledger.record("HISTORICAL_DECISION_REVISED", "gzclp_session_exercises", it.id.toString(),
                it.sessionId, before, it.progressionReason)
        }
        val session = dao.session(approved.sessionId)?.session ?: error("Session disappeared")
        val sessionBefore = ledger.snapshot("gzclp_sessions", approved.sessionId.toString())
        dao.upsertSession(session.copy(historicalCorrection = false))
        ledger.record("HISTORICAL_CORRECTION_ACCEPTED", "gzclp_sessions", approved.sessionId.toString(),
            approved.sessionId, sessionBefore)
        true
    }

    private suspend fun replayPlan(sessionId: Long): Triple<ReplayPreview, Map<String, LiftProgress>, List<GzclpSessionExerciseEntity>> {
        val corrected = dao.session(sessionId) ?: return ReplayPreview(sessionId, emptyList(),
            listOf("Workout no longer exists.")).let { Triple(it, emptyMap(), emptyList()) }
        if (corrected.session.status != "COMPLETED" || !corrected.session.historicalCorrection)
            return Triple(ReplayPreview(sessionId, emptyList(), listOf("This workout has no pending correction.")),
                emptyMap(), emptyList())
        val sessions = dao.completedSessionsAsc().filter {
            (it.finishedAt ?: 0L) >= (corrected.session.finishedAt ?: 0L)
        }.mapNotNull { dao.session(it.id) }
        val progressSnapshot = corrected.exercises.mapNotNull { dao.progress(it.exercise.progressionKey) }
        val sourceFingerprint = listOf(sessions, progressSnapshot).hashCode()
        val active = dao.active()?.let { dao.session(it.id) }
        val conflicts = mutableListOf<String>()
        val changes = mutableListOf<ReplayChange>()
        val updates = mutableMapOf<String, LiftProgress>()
        val historicalDecisions = mutableListOf<GzclpSessionExerciseEntity>()
        corrected.exercises.forEach { first ->
            val ex = first.exercise
            val key = ex.progressionKey
            val current = dao.progress(key) ?: return@forEach
            val inc = ex.baselineIncrementGrams
            val cycle = ex.baselineCycleStartGrams
            val pending = ex.baselinePendingReset
            if (inc == null || cycle == null || pending == null) {
                conflicts += "${ex.exerciseName} ${ex.tier}: this older workout has no recorded progression baseline; set the present load manually after reviewing history."
                return@forEach
            }
            if (active?.exercises?.any { it.exercise.progressionKey == key } == true) {
                conflicts += "${ex.exerciseName} ${ex.tier}: an active workout uses this lift. Finish or discard it before replay."
                return@forEach
            }
            var replayed = LiftProgress(ex.exerciseId, Tier.valueOf(ex.tier), Stage.valueOf(ex.stage),
                ex.recommendedGrams, inc, cycle, pending)
            var originalExpected: LiftProgress? = null
            var reason = current.lastReason
            sessions.forEach { session ->
                val item = session.exercises.firstOrNull { it.exercise.progressionKey == key } ?: return@forEach
                val recorded = item.exercise
                val recordedInc = recorded.baselineIncrementGrams
                val recordedCycle = recorded.baselineCycleStartGrams
                val recordedPending = recorded.baselinePendingReset
                if (recordedInc == null || recordedCycle == null || recordedPending == null) {
                    conflicts += "${recorded.exerciseName} ${recorded.tier}: a later workout lacks replay data."
                    return@forEach
                }
                val original = LiftProgress(recorded.exerciseId, Tier.valueOf(recorded.tier),
                    Stage.valueOf(recorded.stage), recorded.recommendedGrams, recordedInc,
                    recordedCycle, recordedPending)
                if (originalExpected != null && (original.stage != originalExpected!!.stage ||
                        original.recommendedGrams != originalExpected!!.recommendedGrams ||
                        original.incrementGrams != originalExpected!!.incrementGrams ||
                        original.pendingReset != originalExpected!!.pendingReset)) {
                    conflicts += "${recorded.exerciseName} ${recorded.tier}: a later manual override or reset changes the progression path."
                    return@forEach
                }
                if (replayed.stage != original.stage ||
                    GzclpEngine.prescription(replayed.tier, replayed.stage).sets != recorded.targetSets) {
                    conflicts += "${recorded.exerciseName} ${recorded.tier}: the corrected rep scheme differs from a later completed workout. Review that workout before changing today's target."
                    return@forEach
                }
                val sets = if (recorded.skipped) emptyList() else item.sets.sortedBy { it.position }
                    .map { CompletedSet(it.actualReps, it.actualGrams, it.warmup, it.completed) }
                val decision = GzclpEngine.evaluate(replayed, sets)
                replayed = decision.next
                reason = decision.reason
                historicalDecisions += recorded.copy(progressionReason = decision.reason,
                    nextRecommendedGrams = decision.next.recommendedGrams,
                    nextStage = decision.next.stage.name)
                val originalDecision = GzclpEngine.evaluate(original, sets)
                // The finished session retains its original decision, even after its actual
                // sets are corrected. Use that persisted decision to detect later overrides.
                originalExpected = originalDecision.next.copy(
                    stage = recorded.nextStage?.let(Stage::valueOf) ?: originalDecision.next.stage,
                    recommendedGrams = recorded.nextRecommendedGrams ?: originalDecision.next.recommendedGrams)
            }
            if (conflicts.none { it.startsWith("${ex.exerciseName} ${ex.tier}:") }) {
                if (originalExpected != null && (current.stage != originalExpected!!.stage.name ||
                        current.recommendedGrams != originalExpected!!.recommendedGrams ||
                        current.incrementGrams != originalExpected!!.incrementGrams ||
                        current.pendingReset != originalExpected!!.pendingReset)) {
                    conflicts += "${ex.exerciseName} ${ex.tier}: present progression was manually changed after the last workout."
                } else {
                    updates[key] = replayed
                    if (replayed != current.toDomain()) changes += ReplayChange(key, ex.exerciseName, ex.tier,
                        current.recommendedGrams, replayed.recommendedGrams, current.stage,
                        replayed.stage.name, reason)
                }
            }
        }
        return Triple(ReplayPreview(sessionId, changes, conflicts.distinct(), sourceFingerprint),
            updates, historicalDecisions)
    }

    suspend fun changeRest(id: Long, action: String): GzclpSessionEntity? = database.withTransaction {
        val session = dao.session(id)?.session ?: return@withTransaction null
        if (session.status != "ACTIVE") return@withTransaction null
        val now = System.currentTimeMillis()
        val timer = RestDeadline(session.restDeadlineMillis, session.restPausedMillis)
        val changed = when (action) {
            "skip" -> RestDeadline(0)
            "pause" -> timer.pause(now)
            "resume" -> timer.resume(now)
            "plus" -> timer.adjust(now, 15_000)
            "minus" -> timer.adjust(now, -15_000)
            else -> return@withTransaction session
        }
        val updated = session.copy(restDeadlineMillis = changed.deadlineMillis,
            restPausedMillis = changed.pausedMillis)
        val before = ledger.snapshot("gzclp_sessions", id.toString())
        dao.upsertSession(updated)
        ledger.record("REST_${action.uppercase()}", "gzclp_sessions", id.toString(), id, before)
        updated
    }

    suspend fun confirmT1Reset(progressionKey: String, fiveRepMaxGrams: Long) = database.withTransaction {
        val current = dao.progress(progressionKey) ?: error("Progression missing")
        val decision = GzclpEngine.confirmT1Reset(current.toDomain(), fiveRepMaxGrams)
        val before = ledger.snapshot("gzclp_progress", progressionKey)
        dao.upsertProgress(current.fromDecision(decision))
        ledger.record("T1_RESET", "gzclp_progress", progressionKey, before = before,
            reason = "Confirmed 5RM: $fiveRepMaxGrams grams; ${decision.reason}")
    }

    suspend fun confirmT2Reset(progressionKey: String, confirmedGrams: Long) = database.withTransaction {
        val current = dao.progress(progressionKey) ?: error("Progression missing")
        val unit = WeightUnit.valueOf((dao.program() ?: error("Program missing")).unit)
        val decision = GzclpEngine.confirmT2Reset(current.toDomain(), confirmedGrams, unit)
        val before = ledger.snapshot("gzclp_progress", progressionKey)
        dao.upsertProgress(current.fromDecision(decision))
        ledger.record("T2_RESET", "gzclp_progress", progressionKey, before = before,
            reason = "Confirmed load: $confirmedGrams grams; ${decision.reason}")
    }

    /** Existing active sessions are reused after process death or repeated taps. */
    suspend fun startSession(workoutIndex: Int): Long = database.withTransaction {
        dao.active()?.let { return@withTransaction it.id }
        val configured = dao.program() ?: error("Configure GZCLP first")
        require(workoutIndex == configured.nextWorkoutIndex) { "Expected workout ${configured.nextWorkoutIndex}" }
        val now = System.currentTimeMillis()
        val sessionId = dao.insertSession(GzclpSessionEntity(workoutIndex = workoutIndex,
            startedAt = now, activeElapsedMillis = 0, activeSinceMillis = now))
        ledger.record("SESSION_STARTED", "gzclp_sessions", sessionId.toString(), sessionId, before = null)
        val plan = trainingData.readyPlan()?.takeIf { it.workoutIndex == workoutIndex }
            ?: createReadyPlan(workoutIndex)
        val planBefore = ledger.snapshot("training_plans", plan.id)
        trainingData.upsertPlan(plan.copy(status = "STARTED", sessionId = sessionId,
            startedAtMillis = now))
        ledger.record("PLAN_STARTED", "training_plans", plan.id, sessionId, planBefore)
        dao.templatesForWorkout(workoutIndex).forEach { template ->
            val state = dao.progress(template.progressionKey)
                ?: error("Missing progression for ${template.progressionKey}")
            require(!state.pendingReset) { "Reset ${template.exerciseName} before starting this workout" }
            val prescription = GzclpEngine.prescription(Tier.valueOf(template.tier), Stage.valueOf(state.stage))
            val exerciseId = dao.insertExercise(
                GzclpSessionExerciseEntity(
                    sessionId = sessionId,
                    templateId = template.id,
                    progressionKey = template.progressionKey,
                    exerciseId = template.exerciseId,
                    exerciseName = template.exerciseName,
                    tier = template.tier,
                    stage = state.stage,
                    position = template.position,
                    recommendedGrams = state.recommendedGrams,
                    targetSets = prescription.sets,
                    targetReps = prescription.reps,
                    cue = template.cue,
                    restSeconds = template.restSeconds,
                    loadMeaning = loadMeaning(template.exerciseId),
                    baselineIncrementGrams = state.incrementGrams,
                    baselineCycleStartGrams = state.cycleStartGrams,
                    baselinePendingReset = state.pendingReset
                )
            )
            ledger.record("EXERCISE_PRESCRIBED", "gzclp_session_exercises", exerciseId.toString(),
                sessionId, before = null)
            repeat(prescription.sets) { index ->
                val setId = dao.upsertSet(GzclpSetEntity(
                    sessionExerciseId = exerciseId,
                    position = index,
                    targetReps = prescription.reps,
                    actualReps = prescription.reps,
                    targetGrams = state.recommendedGrams,
                    actualGrams = state.recommendedGrams,
                    amrap = prescription.amrap && index == prescription.sets - 1,
                    createdAtMillis = System.currentTimeMillis()
                ))
                ledger.record("SET_PRESCRIBED", "gzclp_sets", setId.toString(), sessionId, before = null)
            }
        }
        sessionId
    }

    suspend fun saveSet(value: GzclpSetEntity) = database.withTransaction {
        val owner = findOwner(value.sessionExerciseId) ?: error("Exercise missing")
        val item = dao.session(owner.id)?.exercises?.firstOrNull { it.exercise.id == value.sessionExerciseId }
            ?: error("Exercise missing")
        val previous = if (value.id == 0L) null else dao.set(value.id)
        require(previous == null || previous.sessionExerciseId == value.sessionExerciseId)
        val changedWorkingLoad = owner.status == "ACTIVE" && item.exercise.tier == "T1" &&
            previous != null && !previous.completed && !value.warmup && value.actualGrams > 0 &&
            previous.actualGrams != value.actualGrams
        val now = System.currentTimeMillis()
        if (changedWorkingLoad) {
            item.sets.filter { !it.warmup && !it.completed && it.id != value.id }.forEach {
                val before = ledger.snapshot("gzclp_sets", it.id.toString())
                val updated = it.copy(targetGrams = value.actualGrams, actualGrams = value.actualGrams,
                    lastEditedAtMillis = now)
                dao.upsertSet(updated)
                ledger.record("SET_EDITED", "gzclp_sets", it.id.toString(), owner.id, before,
                    observedAtMillis = now)
            }
            val exerciseBefore = ledger.snapshot("gzclp_session_exercises", item.exercise.id.toString())
            dao.upsertExercise(item.exercise.copy(recommendedGrams = value.actualGrams))
            ledger.record("EXERCISE_LOAD_CHANGED", "gzclp_session_exercises", item.exercise.id.toString(),
                owner.id, exerciseBefore, observedAtMillis = now)
        }
        val next = if (changedWorkingLoad) value.copy(targetGrams = value.actualGrams) else value
        val saved = next.copy(
            createdAtMillis = previous?.createdAtMillis ?: value.createdAtMillis ?: now,
            completedAtMillis = when {
                !next.completed -> null
                previous?.completed == true -> previous.completedAtMillis
                else -> next.completedAtMillis ?: now
            },
            lastEditedAtMillis = if (previous == null || next.copy(createdAtMillis = previous.createdAtMillis,
                completedAtMillis = previous.completedAtMillis, lastEditedAtMillis = previous.lastEditedAtMillis) != previous)
                now else previous.lastEditedAtMillis
        )
        if (previous == saved) return@withTransaction
        val before = previous?.let { ledger.snapshot("gzclp_sets", it.id.toString()) }
        val setId = dao.upsertSet(saved)
        val kind = when {
            previous == null -> "SET_ADDED"
            previous.completed != saved.completed && saved.completed -> "SET_COMPLETED"
            previous.completed != saved.completed -> "SET_COMPLETION_UNDONE"
            owner.status == "COMPLETED" -> "HISTORICAL_SET_CORRECTED"
            else -> "SET_EDITED"
        }
        ledger.record(kind, "gzclp_sets", setId.toString(), owner.id, before,
            observedAtMillis = if (kind == "SET_COMPLETED") saved.completedAtMillis else now)
        if (owner.status == "COMPLETED") dao.upsertSession(owner.copy(historicalCorrection = true))
    }

    suspend fun deleteSet(id: Long) = database.withTransaction {
        val owner = dao.sessionForSet(id)
        val before = ledger.snapshot("gzclp_sets", id.toString())
        dao.deleteSet(id)
        if (before != null) ledger.record("SET_DELETED", "gzclp_sets", id.toString(), owner?.id, before)
        if (owner?.status == "COMPLETED") dao.upsertSession(owner.copy(historicalCorrection = true))
    }

    suspend fun skipPendingWarmups(exerciseId: Long) = skipPendingSets(exerciseId, warmup = true)

    suspend fun skipPendingWorkingSets(exerciseId: Long) = skipPendingSets(exerciseId, warmup = false)

    private suspend fun skipPendingSets(exerciseId: Long, warmup: Boolean) = database.withTransaction {
        val exercise = activeExercise(exerciseId)
        exercise.sets.filter { it.warmup == warmup && !it.completed }.forEach { dao.deleteSet(it.id) }
    }

    suspend fun generateWarmups(exerciseId: Long, replacePending: Boolean = false) = database.withTransaction {
        val (session, item) = sessionExercise(exerciseId)
        require(session.status == "ACTIVE")
        val program = dao.program() ?: error("Program missing")
        val inventory = program.plateInventory.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            val grams = parts.getOrNull(0)?.toLongOrNull()
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (grams != null && count != null && grams > 0 && count >= 0) Plate(grams, count) else null
        }
        val existing = item.sets.filter { it.warmup }
        // A second tap or process recreation cannot erase logged or edited warm-ups.
        if (existing.isNotEmpty() && !replacePending) return@withTransaction
        // The UI explicitly reviews replacement of pending rows. Completed rows are immutable.
        if (replacePending) existing.filterNot { it.completed }.forEach {
            val before = ledger.snapshot("gzclp_sets", it.id.toString())
            dao.deleteSet(it.id)
            ledger.record("WARMUP_REGENERATED", "gzclp_sets", it.id.toString(), session.id, before)
        }
        val template = program.warmupTemplate.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            val percent = parts.getOrNull(0)?.toIntOrNull()
            val reps = parts.getOrNull(1)?.toIntOrNull()
            if (percent != null && percent in 1..99 && reps != null && reps > 0) percent to reps else null
        }
        val completed = existing.filter { it.completed }
        val occupied = completed.map { it.position }.toMutableSet()
        WarmupGenerator.generate(item.exercise.recommendedGrams, program.barGrams, inventory, template,
            WeightUnit.valueOf(program.unit))
            .drop(completed.size).forEachIndexed { index, step ->
                val position = (-100 + completed.size + index).let { preferred ->
                    if (preferred !in occupied) preferred
                    else (preferred..-1).first { candidate -> candidate !in occupied }
                }
                occupied += position
                val setId = dao.upsertSet(GzclpSetEntity(sessionExerciseId = exerciseId, position = position,
                    targetReps = step.reps, actualReps = step.reps,
                    targetGrams = step.grams, actualGrams = step.grams, warmup = true,
                    createdAtMillis = System.currentTimeMillis()))
                ledger.record("WARMUP_PRESCRIBED", "gzclp_sets", setId.toString(), session.id, before = null)
            }
    }

    private suspend fun sessionExercise(
        exerciseId: Long,
        missingSessionMessage: String = "Exercise missing"
    ): Pair<GzclpSessionEntity, GzclpExerciseWithSets> {
        val session = dao.sessionForExercise(exerciseId) ?: error(missingSessionMessage)
        val exercise = dao.session(session.id)?.exercises?.firstOrNull { it.exercise.id == exerciseId }
            ?: error("Exercise missing")
        return session to exercise
    }

    private suspend fun activeExercise(exerciseId: Long): GzclpExerciseWithSets {
        val (session, exercise) = sessionExercise(exerciseId)
        require(session.status == "ACTIVE")
        return exercise
    }

    suspend fun saveExercise(value: GzclpSessionExerciseEntity) = database.withTransaction {
        val owner = dao.session(value.sessionId)?.session ?: error("Session missing")
        val before = ledger.snapshot("gzclp_session_exercises", value.id.toString())
        dao.upsertExercise(value)
        ledger.record(if (owner.status == "COMPLETED") "HISTORICAL_EXERCISE_CORRECTED" else "EXERCISE_EDITED",
            "gzclp_session_exercises", value.id.toString(), owner.id, before)
        if (owner.status == "COMPLETED") dao.upsertSession(owner.copy(historicalCorrection = true))
    }

    suspend fun saveSession(value: GzclpSessionEntity) = database.withTransaction {
        val current = dao.session(value.id)?.session ?: error("Session missing")
        // Completion and rotation are exclusively controlled by finishSession.
        val before = ledger.snapshot("gzclp_sessions", value.id.toString())
        dao.upsertSession(value.copy(status = current.status,
            progressionApplied = current.progressionApplied,
            finishedAt = current.finishedAt,
            activeElapsedMillis = current.activeElapsedMillis,
            activeSinceMillis = current.activeSinceMillis,
            workoutIndex = current.workoutIndex,
            historicalCorrection = current.historicalCorrection || current.status == "COMPLETED"))
        val kind = if (value.restDeadlineMillis != current.restDeadlineMillis ||
            value.restPausedMillis != current.restPausedMillis) "REST_CHANGED" else "SESSION_EDITED"
        ledger.record(kind, "gzclp_sessions", value.id.toString(), value.id, before)
    }

    suspend fun pauseSessionTimer(id: Long, nowMillis: Long = System.currentTimeMillis()) = database.withTransaction {
        val session = dao.session(id)?.session ?: error("Session missing")
        require(session.status == "ACTIVE")
        val paused = SessionClock(session.activeElapsedMillis, session.activeSinceMillis).pause(nowMillis)
        require(paused.accumulatedMillis != null) { "Set the active duration for this older session first." }
        val before = ledger.snapshot("gzclp_sessions", id.toString())
        dao.upsertSession(session.copy(activeElapsedMillis = paused.accumulatedMillis, activeSinceMillis = null))
        ledger.record("SESSION_TIMER_PAUSED", "gzclp_sessions", id.toString(), id, before,
            observedAtMillis = nowMillis)
    }

    suspend fun resumeSessionTimer(id: Long, nowMillis: Long = System.currentTimeMillis()) = database.withTransaction {
        val session = dao.session(id)?.session ?: error("Session missing")
        require(session.status == "ACTIVE")
        val resumed = SessionClock(session.activeElapsedMillis, session.activeSinceMillis).resume(nowMillis)
        val before = ledger.snapshot("gzclp_sessions", id.toString())
        dao.upsertSession(session.copy(activeSinceMillis = resumed.runningSinceMillis))
        ledger.record("SESSION_TIMER_RESUMED", "gzclp_sessions", id.toString(), id, before,
            observedAtMillis = nowMillis)
    }

    suspend fun resolveSessionTimer(id: Long, activeMinutes: Long) = database.withTransaction {
        val session = dao.session(id)?.session ?: error("Session missing")
        require(session.status == "ACTIVE")
        val resolved = SessionClock(session.activeElapsedMillis, session.activeSinceMillis).resolve(activeMinutes)
        val before = ledger.snapshot("gzclp_sessions", id.toString())
        dao.upsertSession(session.copy(activeElapsedMillis = resolved.accumulatedMillis, activeSinceMillis = null))
        ledger.record("SESSION_TIMER_RESOLVED", "gzclp_sessions", id.toString(), id, before,
            reason = "User supplied ${activeMinutes.coerceAtLeast(0)} active minutes")
    }

    /** Returns false if already finished. One transaction owns set evaluation, updates, and rotation. */
    suspend fun finishSession(id: Long): Boolean = database.withTransaction {
        val details = dao.session(id) ?: return@withTransaction false
        val session = details.session
        if (session.status != "ACTIVE" || session.progressionApplied) return@withTransaction false
        val now = System.currentTimeMillis()
        val elapsed = SessionClock(session.activeElapsedMillis, session.activeSinceMillis).elapsed(now)
            ?: error("Set the active duration for this older session before finishing.")
        details.exercises.forEach { item ->
            val exercise = item.exercise
            val old = dao.progress(exercise.progressionKey)
            if (old == null) {
                val before = ledger.snapshot("gzclp_session_exercises", exercise.id.toString())
                dao.upsertExercise(exercise.copy(progressionReason = "Session substitution; original lift unchanged"))
                ledger.record("SUBSTITUTION_PROGRESS_ISOLATED", "gzclp_session_exercises", exercise.id.toString(),
                    id, before, "Original lift progression unchanged")
                return@forEach
            }
            val model = LiftProgress(
                exerciseId = old.exerciseId,
                tier = Tier.valueOf(old.tier),
                stage = Stage.valueOf(exercise.stage),
                recommendedGrams = exercise.recommendedGrams,
                incrementGrams = old.incrementGrams,
                cycleStartGrams = old.cycleStartGrams,
                pendingReset = old.pendingReset
            )
            val completed = if (exercise.skipped) emptyList() else item.sets.sortedBy { it.position }.map {
                CompletedSet(it.actualReps, it.actualGrams, it.warmup, it.completed)
            }
            val decision = GzclpEngine.evaluate(model, completed)
            val exerciseBefore = ledger.snapshot("gzclp_session_exercises", exercise.id.toString())
            dao.upsertExercise(exercise.copy(
                progressionReason = decision.reason,
                nextRecommendedGrams = decision.next.recommendedGrams,
                nextStage = decision.next.stage.name
            ))
            ledger.record("PROGRESSION_DECIDED", "gzclp_session_exercises", exercise.id.toString(),
                id, exerciseBefore, decision.reason)
            if (decision.status != org.librefit.gzclp.domain.DecisionStatus.SKIPPED) {
                val progressBefore = ledger.snapshot("gzclp_progress", exercise.progressionKey)
                dao.upsertProgress(old.copy(
                    stage = decision.next.stage.name,
                    recommendedGrams = decision.next.recommendedGrams,
                    cycleStartGrams = decision.next.cycleStartGrams,
                    pendingReset = decision.next.pendingReset,
                    lastReason = decision.reason,
                    updatedAt = System.currentTimeMillis()
                ))
                ledger.record("PROGRESSION_APPLIED", "gzclp_progress", exercise.progressionKey,
                    id, progressBefore, decision.reason)
            }
        }
        val sessionBefore = ledger.snapshot("gzclp_sessions", id.toString())
        dao.upsertSession(session.copy(status = "COMPLETED", finishedAt = now,
            activeElapsedMillis = elapsed, activeSinceMillis = null, progressionApplied = true))
        ledger.record("SESSION_COMPLETED", "gzclp_sessions", id.toString(), id, sessionBefore)
        val program = dao.program() ?: error("Program missing")
        val programBefore = ledger.snapshot("gzclp_program", "1")
        dao.upsertProgram(program.copy(nextWorkoutIndex = (session.workoutIndex + 1) % 4))
        ledger.record("ROTATION_ADVANCED", "gzclp_program", "1", id, programBefore)
        trainingData.planForSession(id)?.let { plan ->
            val before = ledger.snapshot("training_plans", plan.id)
            trainingData.upsertPlan(plan.copy(status = "COMPLETED", completedAtMillis = now))
            ledger.record("PLAN_COMPLETED", "training_plans", plan.id, id, before)
        }
        createReadyPlan((session.workoutIndex + 1) % 4)
        true
    }

    suspend fun abandonSession(id: Long) = database.withTransaction {
        val current = dao.session(id)?.session ?: return@withTransaction
        if (current.status == "ACTIVE") {
            val before = ledger.snapshot("gzclp_sessions", id.toString())
            dao.upsertSession(current.copy(status = "ABANDONED"))
            ledger.record("SESSION_ABANDONED", "gzclp_sessions", id.toString(), id, before)
            trainingData.planForSession(id)?.let { plan ->
                val planBefore = ledger.snapshot("training_plans", plan.id)
                trainingData.upsertPlan(plan.copy(status = "ABANDONED"))
                ledger.record("PLAN_ABANDONED", "training_plans", plan.id, id, planBefore)
            }
            createReadyPlan(current.workoutIndex)
        }
    }

    private suspend fun findOwner(exerciseId: Long): GzclpSessionEntity? =
        dao.sessionForExercise(exerciseId)

    private fun GzclpProgressEntity.toDomain() = LiftProgress(
        exerciseId = exerciseId,
        tier = Tier.valueOf(tier),
        stage = Stage.valueOf(stage),
        recommendedGrams = recommendedGrams,
        incrementGrams = incrementGrams,
        cycleStartGrams = cycleStartGrams,
        pendingReset = pendingReset
    )

    private fun GzclpProgressEntity.fromDecision(decision: org.librefit.gzclp.domain.ProgressionDecision) = copy(
        stage = decision.next.stage.name,
        recommendedGrams = decision.next.recommendedGrams,
        cycleStartGrams = decision.next.cycleStartGrams,
        pendingReset = decision.next.pendingReset,
        lastReason = decision.reason,
        updatedAt = System.currentTimeMillis()
    )
}
