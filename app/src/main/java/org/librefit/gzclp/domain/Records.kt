/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.domain

data class Performance(val exerciseId: String, val grams: Long, val reps: Int, val warmup: Boolean = false)
data class ExerciseRecords(
    val heaviestGrams: Long?,
    val bestRepsByWeight: Map<Long, Int>,
    val estimatedOneRepMaxGrams: Long?
)

data class RecordSession(val sessionId: Long, val date: Long, val sets: List<Performance>)
enum class RecordKind { BASELINE, HEAVIEST, REPS_AT_WEIGHT, ESTIMATED_ONE_REP_MAX, SESSION_VOLUME }
data class RecordEvent(val sessionId: Long, val date: Long, val kind: RecordKind,
    val grams: Long = 0L, val reps: Int = 0)

/** Epley estimate applies only to valid 1–10 rep working sets. */
object Records {
    /** Rebuilt from completed history so edits cannot leave stale record flags behind. */
    fun history(sessions: List<RecordSession>): List<RecordEvent> {
        val events = mutableListOf<RecordEvent>()
        val previous = mutableListOf<Performance>()
        var bestVolume = 0L
        sessions.sortedWith(compareBy<RecordSession> { it.date }.thenBy { it.sessionId }).forEach { session ->
            val valid = session.sets.filter { !it.warmup && it.grams > 0 && it.reps > 0 }
            if (valid.isEmpty()) return@forEach
            val exerciseId = valid.first().exerciseId
            val before = forExercise(previous, exerciseId)
            if (previous.isEmpty()) events += RecordEvent(session.sessionId, session.date, RecordKind.BASELINE)
            else {
                val heaviest = valid.maxOf { it.grams }
                if (heaviest > (before.heaviestGrams ?: 0L))
                    events += RecordEvent(session.sessionId, session.date, RecordKind.HEAVIEST, heaviest)
                valid.groupBy { it.grams }.forEach { (grams, sets) ->
                    val reps = sets.maxOf { it.reps }
                    if (grams <= (before.heaviestGrams ?: 0L) && reps > (before.bestRepsByWeight[grams] ?: 0))
                        events += RecordEvent(session.sessionId, session.date, RecordKind.REPS_AT_WEIGHT, grams, reps)
                }
                val estimate = valid.filter { it.reps <= 10 }
                    .maxOfOrNull { (it.grams * (1.0 + it.reps / 30.0)).toLong() }
                if (estimate != null && estimate > (before.estimatedOneRepMaxGrams ?: 0L))
                    events += RecordEvent(session.sessionId, session.date, RecordKind.ESTIMATED_ONE_REP_MAX, estimate)
            }
            val volume = workingVolume(valid)
            if (bestVolume > 0L && volume > bestVolume)
                events += RecordEvent(session.sessionId, session.date, RecordKind.SESSION_VOLUME, volume)
            bestVolume = maxOf(bestVolume, volume)
            previous += valid
        }
        return events
    }

    fun workingVolume(sets: List<Performance>): Long =
        sets.filter { !it.warmup && it.grams > 0 && it.reps > 0 }.sumOf { it.grams * it.reps }

    fun bestInRepRange(sets: List<Performance>, minimum: Int, maximum: Int): Performance? =
        sets.filter { !it.warmup && it.reps in minimum..maximum && it.grams > 0 }
            .maxWithOrNull(compareBy<Performance> { it.grams }.thenBy { it.reps })

    fun forExercise(sets: List<Performance>, exerciseId: String): ExerciseRecords {
        val valid = sets.filter { it.exerciseId == exerciseId && !it.warmup && it.grams > 0 && it.reps > 0 }
        return ExerciseRecords(valid.maxOfOrNull { it.grams },
            valid.groupBy { it.grams }.mapValues { (_, performances) -> performances.maxOf { it.reps } },
            valid.filter { it.reps <= 10 }.maxOfOrNull { (it.grams * (1.0 + it.reps / 30.0)).toLong() })
    }

    fun newRecords(previous: ExerciseRecords, performance: Performance): List<String> {
        if (performance.warmup || performance.grams <= 0 || performance.reps <= 0) return emptyList()
        val result = mutableListOf<String>()
        if (previous.heaviestGrams == null || performance.grams > previous.heaviestGrams) result += "Heaviest set"
        if (performance.reps > (previous.bestRepsByWeight[performance.grams] ?: 0)) result += "Rep record at this weight"
        val estimate = if (performance.reps <= 10) (performance.grams * (1.0 + performance.reps / 30.0)).toLong() else null
        if (estimate != null && (previous.estimatedOneRepMaxGrams == null || estimate > previous.estimatedOneRepMaxGrams))
            result += "Estimated 1RM record"
        return result
    }
}
