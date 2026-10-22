/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.librefit.gzclp.domain

import kotlin.math.abs

data class Plate(val grams: Long, val count: Int)
data class PlateLoading(val perSide: List<Plate>, val actualGrams: Long, val differenceGrams: Long)

/** Bounded subset-sum: count is the total inventory, not the count per side. */
object PlateCalculator {
    fun calculate(targetGrams: Long, barGrams: Long, inventory: List<Plate>): PlateLoading {
        require(targetGrams >= 0 && barGrams >= 0)
        require(inventory.all { it.grams > 0 && it.count >= 0 })
        val possible = mutableMapOf(0L to emptyList<Plate>())
        inventory.sortedByDescending { it.grams }.forEach { plate ->
            val previous = possible.toMap()
            previous.forEach { (weight, selected) ->
                for (count in 1..plate.count / 2) {
                    val next = weight + plate.grams * count
                    val candidate = selected + Plate(plate.grams, count)
                    if (possible[next] == null || candidate.sumOf { it.count } < possible[next]!!.sumOf { it.count })
                        possible[next] = candidate
                }
            }
        }
        val best = possible.minWith(compareBy<Map.Entry<Long, List<Plate>>> {
            abs(barGrams + it.key * 2 - targetGrams)
        }.thenBy { it.value.sumOf(Plate::count) }.thenBy { it.key })
        val actual = barGrams + best.key * 2
        return PlateLoading(best.value, actual, actual - targetGrams)
    }
}

data class WarmupStep(val grams: Long, val reps: Int)

object WarmupGenerator {
    fun generate(
        targetGrams: Long, barGrams: Long, inventory: List<Plate>,
        template: List<Pair<Int, Int>> = listOf(40 to 5, 60 to 3, 80 to 2)
    ): List<WarmupStep> {
        if (targetGrams <= barGrams || barGrams <= 0) return emptyList()
        val result = mutableListOf(WarmupStep(barGrams, 5))
        template.forEach { (percent, reps) ->
            require(percent in 1..99 && reps > 0)
            val desired = (targetGrams * percent / 100).coerceAtLeast(barGrams)
            val actual = PlateCalculator.calculate(desired, barGrams, inventory).actualGrams
            if (actual < targetGrams && actual > result.last().grams)
                result += WarmupStep(actual, reps)
        }
        return result
    }
}

/** A wall-clock deadline remains correct after backgrounding or process recreation. */
data class RestDeadline(val deadlineMillis: Long, val pausedMillis: Long = 0) {
    fun remaining(now: Long): Long = if (pausedMillis > 0) pausedMillis else (deadlineMillis - now).coerceAtLeast(0)
    fun pause(now: Long) = RestDeadline(0, remaining(now))
    fun resume(now: Long) = RestDeadline(now + pausedMillis.coerceAtLeast(0))
    fun adjust(now: Long, deltaMillis: Long): RestDeadline =
        if (pausedMillis > 0) copy(pausedMillis = (pausedMillis + deltaMillis).coerceAtLeast(0))
        else copy(deadlineMillis = (deadlineMillis.coerceAtLeast(now) + deltaMillis).coerceAtLeast(now))
}
