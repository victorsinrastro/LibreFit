/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.db.repository

import androidx.room.withTransaction
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
    val program: Flow<GzclpProgramEntity?> = dao.observeProgram()
    val templates: Flow<List<GzclpTemplateExerciseEntity>> = dao.observeTemplates()
    val progress: Flow<List<GzclpProgressEntity>> = dao.observeProgress()
    val activeSession: Flow<GzclpSessionWithDetails?> = dao.observeActive()
    val history: Flow<List<GzclpSessionEntity>> = dao.observeHistory()

    suspend fun configure(
        program: GzclpProgramEntity,
        templates: List<GzclpTemplateExerciseEntity>,
        progress: List<GzclpProgressEntity>
    ) = database.withTransaction {
        require(dao.program() == null) { "Program is already configured; use settings methods." }
        require(program.daysPerWeek == 3 || program.daysPerWeek == 4)
        dao.upsertProgram(program.copy(id = 1, nextWorkoutIndex = 0))
        dao.upsertTemplates(templates)
        dao.upsertProgress(progress)
    }

    suspend fun saveProgram(value: GzclpProgramEntity) = database.withTransaction {
        require(value.daysPerWeek == 3 || value.daysPerWeek == 4)
        val current = dao.program() ?: error("Configure GZCLP first")
        dao.upsertProgram(value.copy(id = 1, nextWorkoutIndex = current.nextWorkoutIndex))
    }

    suspend fun saveTemplate(value: GzclpTemplateExerciseEntity) = dao.upsertTemplate(value)
    suspend fun deleteTemplate(id: String) = dao.deleteTemplate(id)
    suspend fun saveProgress(value: GzclpProgressEntity) = dao.upsertProgress(value)
    suspend fun getSession(id: Long): GzclpSessionWithDetails? = dao.session(id)

    /** A session replacement has no program progression. Recorded sets cannot be relabeled. */
    suspend fun substituteSessionExercise(exerciseId: Long, replacementId: String,
        replacementName: String, workingGrams: Long) = database.withTransaction {
        require(replacementId.isNotBlank() && replacementName.isNotBlank() && workingGrams > 0)
        val owner = dao.sessionForExercise(exerciseId) ?: error("Workout missing")
        require(owner.status == "ACTIVE") { "Only an active workout can be changed" }
        val item = dao.session(owner.id)?.exercises?.firstOrNull { it.exercise.id == exerciseId }
            ?: error("Exercise missing")
        require(item.sets.none { it.completed }) { "Recorded sets must stay with their original exercise" }
        require(item.sets.none { it.warmup }) { "Remove pending warm-ups before substituting" }
        val current = item.exercise
        val template = dao.templatesForWorkout(owner.workoutIndex).firstOrNull {
            it.id == current.templateId } ?: error("Original program exercise missing")
        require(template.exerciseId == current.exerciseId &&
            template.progressionKey == current.progressionKey) {
            "Undo the future program change before replacing today's lift"
        }
        dao.upsertExercise(current.copy(exerciseId = replacementId, exerciseName = replacementName,
            progressionKey = "session:$exerciseId:$replacementId:${current.tier}",
            recommendedGrams = workingGrams))
        item.sets.forEach { dao.upsertSet(it.copy(targetGrams = workingGrams,
            actualGrams = workingGrams)) }
    }

    suspend fun restoreSessionExercise(exerciseId: Long) = database.withTransaction {
        val owner = dao.sessionForExercise(exerciseId) ?: error("Workout missing")
        require(owner.status == "ACTIVE")
        val item = dao.session(owner.id)?.exercises?.firstOrNull { it.exercise.id == exerciseId }
            ?: error("Exercise missing")
        require(item.exercise.progressionKey.startsWith("session:")) { "No session replacement to undo" }
        require(item.sets.none { it.completed }) { "Recorded sets must stay with their replacement exercise" }
        val template = dao.templatesForWorkout(owner.workoutIndex).firstOrNull {
            it.id == item.exercise.templateId } ?: error("Original program exercise missing")
        val original = dao.progress(template.progressionKey) ?: error("Original progression missing")
        dao.upsertExercise(item.exercise.copy(exerciseId = template.exerciseId,
            exerciseName = template.exerciseName, progressionKey = template.progressionKey,
            recommendedGrams = original.recommendedGrams))
        item.sets.forEach { dao.upsertSet(it.copy(targetGrams = original.recommendedGrams,
            actualGrams = original.recommendedGrams)) }
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
        if (dao.progress(key) == null) dao.upsertProgress(GzclpProgressEntity(
            progressionKey = key, exerciseId = replacementId, tier = existing.tier,
            recommendedGrams = startingGrams, incrementGrams = 2_500))
        dao.upsertTemplate(existing.copy(exerciseId = replacementId,
            exerciseName = replacementName, progressionKey = key))
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
            dao.upsertProgress(current.copy(stage = state.stage.name,
                recommendedGrams = state.recommendedGrams, incrementGrams = state.incrementGrams,
                cycleStartGrams = state.cycleStartGrams, pendingReset = state.pendingReset,
                lastReason = fresh.changes.find { it.progressionKey == key }?.reason ?: current.lastReason,
                updatedAt = System.currentTimeMillis()))
        }
        historicalDecisions.forEach { dao.upsertExercise(it) }
        val session = dao.session(approved.sessionId)?.session ?: error("Session disappeared")
        dao.upsertSession(session.copy(historicalCorrection = false))
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
        dao.upsertSession(updated)
        updated
    }

    suspend fun confirmT1Reset(progressionKey: String, fiveRepMaxGrams: Long) = database.withTransaction {
        val current = dao.progress(progressionKey) ?: error("Progression missing")
        val decision = GzclpEngine.confirmT1Reset(current.toDomain(), fiveRepMaxGrams)
        dao.upsertProgress(current.fromDecision(decision))
    }

    suspend fun confirmT2Reset(progressionKey: String, confirmedGrams: Long) = database.withTransaction {
        val current = dao.progress(progressionKey) ?: error("Progression missing")
        val unit = WeightUnit.valueOf((dao.program() ?: error("Program missing")).unit)
        val decision = GzclpEngine.confirmT2Reset(current.toDomain(), confirmedGrams, unit)
        dao.upsertProgress(current.fromDecision(decision))
    }

    /** Existing active sessions are reused after process death or repeated taps. */
    suspend fun startSession(workoutIndex: Int): Long = database.withTransaction {
        dao.active()?.let { return@withTransaction it.id }
        val configured = dao.program() ?: error("Configure GZCLP first")
        require(workoutIndex == configured.nextWorkoutIndex) { "Expected workout ${configured.nextWorkoutIndex}" }
        val sessionId = dao.insertSession(GzclpSessionEntity(workoutIndex = workoutIndex))
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
                    baselineIncrementGrams = state.incrementGrams,
                    baselineCycleStartGrams = state.cycleStartGrams,
                    baselinePendingReset = state.pendingReset
                )
            )
            repeat(prescription.sets) { index ->
                dao.upsertSet(GzclpSetEntity(
                    sessionExerciseId = exerciseId,
                    position = index,
                    targetReps = prescription.reps,
                    actualReps = prescription.reps,
                    targetGrams = state.recommendedGrams,
                    actualGrams = state.recommendedGrams,
                    amrap = prescription.amrap && index == prescription.sets - 1
                ))
            }
        }
        sessionId
    }

    suspend fun saveSet(value: GzclpSetEntity) = database.withTransaction {
        val owner = findOwner(value.sessionExerciseId) ?: error("Exercise missing")
        dao.upsertSet(value)
        if (owner.status == "COMPLETED") dao.upsertSession(owner.copy(historicalCorrection = true))
    }

    suspend fun deleteSet(id: Long) = database.withTransaction {
        val owner = dao.sessionForSet(id)
        dao.deleteSet(id)
        if (owner?.status == "COMPLETED") dao.upsertSession(owner.copy(historicalCorrection = true))
    }

    suspend fun generateWarmups(exerciseId: Long, replacePending: Boolean = false) = database.withTransaction {
        val session = dao.sessionForExercise(exerciseId) ?: error("Exercise missing")
        require(session.status == "ACTIVE")
        val item = dao.session(session.id)?.exercises?.firstOrNull { it.exercise.id == exerciseId }
            ?: error("Exercise missing")
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
        if (replacePending) existing.filterNot { it.completed }.forEach { dao.deleteSet(it.id) }
        val template = program.warmupTemplate.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            val percent = parts.getOrNull(0)?.toIntOrNull()
            val reps = parts.getOrNull(1)?.toIntOrNull()
            if (percent != null && percent in 1..99 && reps != null && reps > 0) percent to reps else null
        }
        val completed = existing.filter { it.completed }
        val occupied = completed.map { it.position }.toMutableSet()
        WarmupGenerator.generate(item.exercise.recommendedGrams, program.barGrams, inventory, template)
            .drop(completed.size).forEachIndexed { index, step ->
                val position = (-100 + completed.size + index).let { preferred ->
                    if (preferred !in occupied) preferred
                    else (preferred..-1).first { candidate -> candidate !in occupied }
                }
                occupied += position
                dao.upsertSet(GzclpSetEntity(sessionExerciseId = exerciseId, position = position,
                    targetReps = step.reps, actualReps = step.reps,
                    targetGrams = step.grams, actualGrams = step.grams, warmup = true))
            }
    }

    suspend fun saveExercise(value: GzclpSessionExerciseEntity) = database.withTransaction {
        val owner = dao.session(value.sessionId)?.session ?: error("Session missing")
        dao.upsertExercise(value)
        if (owner.status == "COMPLETED") dao.upsertSession(owner.copy(historicalCorrection = true))
    }

    suspend fun saveSession(value: GzclpSessionEntity) = database.withTransaction {
        val current = dao.session(value.id)?.session ?: error("Session missing")
        // Completion and rotation are exclusively controlled by finishSession.
        dao.upsertSession(value.copy(status = current.status,
            progressionApplied = current.progressionApplied,
            finishedAt = current.finishedAt,
            workoutIndex = current.workoutIndex,
            historicalCorrection = current.historicalCorrection || current.status == "COMPLETED"))
    }

    /** Returns false if already finished. One transaction owns set evaluation, updates, and rotation. */
    suspend fun finishSession(id: Long): Boolean = database.withTransaction {
        val details = dao.session(id) ?: return@withTransaction false
        val session = details.session
        if (session.status != "ACTIVE" || session.progressionApplied) return@withTransaction false
        details.exercises.forEach { item ->
            val exercise = item.exercise
            val old = dao.progress(exercise.progressionKey)
            if (old == null) {
                dao.upsertExercise(exercise.copy(progressionReason = "Session substitution; original lift unchanged"))
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
            dao.upsertExercise(exercise.copy(
                progressionReason = decision.reason,
                nextRecommendedGrams = decision.next.recommendedGrams,
                nextStage = decision.next.stage.name
            ))
            if (decision.status != org.librefit.gzclp.domain.DecisionStatus.SKIPPED) {
                dao.upsertProgress(old.copy(
                    stage = decision.next.stage.name,
                    recommendedGrams = decision.next.recommendedGrams,
                    cycleStartGrams = decision.next.cycleStartGrams,
                    pendingReset = decision.next.pendingReset,
                    lastReason = decision.reason,
                    updatedAt = System.currentTimeMillis()
                ))
            }
        }
        val now = System.currentTimeMillis()
        dao.upsertSession(session.copy(status = "COMPLETED", finishedAt = now, progressionApplied = true))
        val program = dao.program() ?: error("Program missing")
        dao.upsertProgram(program.copy(nextWorkoutIndex = (session.workoutIndex + 1) % 4))
        true
    }

    suspend fun abandonSession(id: Long) = database.withTransaction {
        val current = dao.session(id)?.session ?: return@withTransaction
        if (current.status == "ACTIVE") dao.upsertSession(current.copy(status = "ABANDONED"))
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
