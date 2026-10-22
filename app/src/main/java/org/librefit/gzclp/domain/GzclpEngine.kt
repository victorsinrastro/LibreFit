package org.librefit.gzclp.domain

import kotlin.math.roundToLong

enum class Tier { T1, T2, T3 }
enum class Stage { FIRST, SECOND, THIRD }
enum class WeightUnit { KG, LB }
enum class DecisionStatus { INCREASE, ADVANCE_STAGE, MAINTAIN, RESET_REQUIRED, RESET_APPLIED, SKIPPED }

/** Stored loads are whole grams; display units never become the source of truth. */
object GzclpWeights {
    fun toGrams(value: Double, unit: WeightUnit): Long =
        (value * if (unit == WeightUnit.KG) 1000.0 else 453.59237).roundToLong()

    fun fromGrams(grams: Long, unit: WeightUnit): Double =
        grams / if (unit == WeightUnit.KG) 1000.0 else 453.59237

    fun roundToIncrement(grams: Long, incrementGrams: Long): Long {
        require(incrementGrams > 0)
        return ((grams.toDouble() / incrementGrams).roundToLong() * incrementGrams).coerceAtLeast(0)
    }
}

data class Prescription(val sets: Int, val reps: Int, val amrap: Boolean) {
    val label: String get() = "$sets×$reps${if (amrap) "+" else ""}"
}

/** A progression key is exercise ID plus tier; the same lift in T1 and T2 is independent. */
data class LiftProgress(
    val exerciseId: String,
    val tier: Tier,
    val stage: Stage = Stage.FIRST,
    val recommendedGrams: Long,
    val incrementGrams: Long,
    val cycleStartGrams: Long = recommendedGrams,
    val pendingReset: Boolean = false,
) {
    init {
        require(recommendedGrams >= 0 && incrementGrams > 0 && cycleStartGrams >= 0)
    }
}

data class CompletedSet(
    val reps: Int,
    val weightGrams: Long,
    val isWarmup: Boolean = false,
    val isComplete: Boolean = true,
) {
    init { require(reps >= 0 && weightGrams >= 0) }
}

data class ProgressionDecision(
    val status: DecisionStatus,
    val next: LiftProgress,
    val reason: String,
    val actualWorkingGrams: Long? = null,
)

object GzclpEngine {
    fun prescription(tier: Tier, stage: Stage = Stage.FIRST): Prescription = when (tier) {
        Tier.T1 -> when (stage) {
            Stage.FIRST -> Prescription(5, 3, true)
            Stage.SECOND -> Prescription(6, 2, true)
            Stage.THIRD -> Prescription(10, 1, true)
        }
        Tier.T2 -> when (stage) {
            Stage.FIRST -> Prescription(3, 10, false)
            Stage.SECOND -> Prescription(3, 8, false)
            Stage.THIRD -> Prescription(3, 6, false)
        }
        Tier.T3 -> Prescription(3, 15, true)
    }

    /** Evaluate once for a finished session. Callers must transactionally guard session completion. */
    fun evaluate(progress: LiftProgress, sets: List<CompletedSet>): ProgressionDecision {
        val working = sets.filter { !it.isWarmup && it.isComplete }
        val target = prescription(progress.tier, progress.stage)
        if (progress.pendingReset) return ProgressionDecision(
            DecisionStatus.RESET_REQUIRED, progress, "Record a new five-rep max to restart this lift."
        )
        if (working.isEmpty()) return ProgressionDecision(DecisionStatus.SKIPPED, progress, "Exercise skipped.")
        val actualLoad = working.last().weightGrams
        val success = working.size >= target.sets &&
            working.take(target.sets).all { it.reps >= target.reps }
        if (progress.tier == Tier.T3) {
            val firstSetsComplete = working.size >= target.sets &&
                working.take(target.sets - 1).all { it.reps >= target.reps }
            val amrap = working.getOrNull(target.sets - 1)?.reps ?: 0
            return if (firstSetsComplete && amrap >= 25) {
                ProgressionDecision(DecisionStatus.INCREASE,
                    progress.copy(recommendedGrams = actualLoad + progress.incrementGrams),
                    "Final AMRAP reached $amrap reps; increase by the configured increment.", actualLoad)
            } else ProgressionDecision(DecisionStatus.MAINTAIN, progress,
                "Keep this weight until the first two sets reach 15 and the final AMRAP reaches 25 reps.", actualLoad)
        }
        if (success) return ProgressionDecision(DecisionStatus.INCREASE,
            progress.copy(recommendedGrams = actualLoad + progress.incrementGrams),
            "All prescribed working sets and reps were completed.", actualLoad)
        if (progress.stage != Stage.THIRD) {
            val nextStage = if (progress.stage == Stage.FIRST) Stage.SECOND else Stage.THIRD
            return ProgressionDecision(DecisionStatus.ADVANCE_STAGE,
                progress.copy(stage = nextStage, recommendedGrams = actualLoad),
                "Prescribed reps were missed; use ${prescription(progress.tier, nextStage).label} at the same weight.", actualLoad)
        }
        return if (progress.tier == Tier.T1) ProgressionDecision(DecisionStatus.RESET_REQUIRED,
            progress.copy(recommendedGrams = actualLoad, pendingReset = true),
            "The 10×1+ target was missed. Rest, test a new five-rep max, then restart at about 85%.", actualLoad)
        else ProgressionDecision(DecisionStatus.RESET_REQUIRED,
            progress.copy(recommendedGrams = actualLoad, pendingReset = true),
            "The 3×6 target was missed. Confirm a new 3×10 starting weight modestly above the previous cycle start.", actualLoad)
    }

    fun confirmT1Reset(progress: LiftProgress, testedFiveRepMaxGrams: Long): ProgressionDecision {
        require(progress.tier == Tier.T1 && progress.pendingReset && testedFiveRepMaxGrams > 0)
        val load = GzclpWeights.roundToIncrement((testedFiveRepMaxGrams * 0.85).roundToLong(), progress.incrementGrams)
        return ProgressionDecision(DecisionStatus.RESET_APPLIED,
            progress.copy(stage = Stage.FIRST, recommendedGrams = load, cycleStartGrams = load, pendingReset = false),
            "Restart 5×3+ at approximately 85% of the tested five-rep max.")
    }

    fun confirmT2Reset(progress: LiftProgress, confirmedGrams: Long, unit: WeightUnit): ProgressionDecision {
        require(progress.tier == Tier.T2 && progress.pendingReset)
        val maxIncrease = GzclpWeights.toGrams(if (unit == WeightUnit.KG) 9.0 else 20.0, unit)
        require(confirmedGrams > progress.cycleStartGrams && confirmedGrams <= progress.cycleStartGrams + maxIncrease)
        return ProgressionDecision(DecisionStatus.RESET_APPLIED,
            progress.copy(stage = Stage.FIRST, recommendedGrams = confirmedGrams,
                cycleStartGrams = confirmedGrams, pendingReset = false),
            "Restart 3×10 with a modest increase over the previous cycle's starting weight.")
    }
}

enum class WorkoutDay { A1, B1, A2, B2 }

object GzclpRotation {
    val days = WorkoutDay.entries
    fun next(completedSessions: Int): WorkoutDay = days[Math.floorMod(completedSessions, days.size)]
    fun week(completedSessions: Int, daysPerWeek: Int): Int {
        require(daysPerWeek == 3 || daysPerWeek == 4)
        return completedSessions / daysPerWeek + 1
    }
}

/** A historical edit changes the recorded sets, not the progression snapshot already accepted. */
data class SessionExerciseResult(
    val progress: LiftProgress,
    val sets: List<CompletedSet>,
    val attempted: Boolean,
)

data class SessionProgressionResult(
    val decisions: List<ProgressionDecision>,
    val applied: Boolean,
)

object GzclpSessionProgression {
    fun evaluate(
        exercises: List<SessionExerciseResult>,
        finished: Boolean,
        progressionAlreadyApplied: Boolean,
    ): SessionProgressionResult {
        if (!finished || progressionAlreadyApplied) return SessionProgressionResult(emptyList(), false)
        return SessionProgressionResult(exercises.map { exercise ->
            if (exercise.attempted) GzclpEngine.evaluate(exercise.progress, exercise.sets)
            else ProgressionDecision(DecisionStatus.SKIPPED, exercise.progress, "Exercise skipped.")
        }, true)
    }
}
