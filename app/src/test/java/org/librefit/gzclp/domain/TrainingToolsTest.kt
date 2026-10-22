package org.librefit.gzclp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingToolsTest {
    @Test fun exactPlateLoadingUsesSymmetricInventory() {
        val result = PlateCalculator.calculate(60_000, 20_000,
            listOf(Plate(20_000, 2), Plate(10_000, 2), Plate(5_000, 2)))
        assertEquals(60_000, result.actualGrams)
        assertEquals(listOf(Plate(20_000, 1)), result.perSide)
    }

    @Test fun constrainedInventoryFindsClosestFeasibleLoad() {
        val result = PlateCalculator.calculate(31_000, 20_000,
            listOf(Plate(5_000, 2), Plate(2_500, 1), Plate(500, 2)))
        assertEquals(31_000, result.actualGrams)
        assertEquals(listOf(Plate(5_000, 1), Plate(500, 1)), result.perSide)
    }

    @Test fun warmupsStayBelowWorkingWeightAndAboveBar() {
        val inventory = listOf(Plate(20_000, 2), Plate(10_000, 2), Plate(5_000, 2), Plate(2_500, 2))
        val steps = WarmupGenerator.generate(60_000, 20_000, inventory)
        assertEquals(20_000, steps.first().grams)
        assertTrue(steps.all { it.grams in 20_000 until 60_000 })
        assertTrue(steps.zipWithNext().all { it.first.grams < it.second.grams })
        assertTrue(WarmupGenerator.generate(15_000, 20_000, inventory).isEmpty())
    }

    @Test fun deadlineSurvivesClockAdvancePauseAndAdjustment() {
        val timer = RestDeadline(190_000)
        assertEquals(90_000, timer.remaining(100_000))
        val paused = timer.pause(120_000)
        assertEquals(70_000, paused.remaining(180_000))
        assertEquals(85_000, paused.adjust(180_000, 15_000).remaining(200_000))
        assertEquals(85_000, paused.adjust(180_000, 15_000).resume(200_000).remaining(200_000))
    }

    @Test fun recordsRecalculateFromActualWorkingSets() {
        val old = listOf(Performance("squat", 60_000, 3), Performance("squat", 40_000, 12, warmup = true))
        val before = Records.forExercise(old, "squat")
        assertEquals(60_000L, before.heaviestGrams)
        assertEquals(3, before.bestRepsByWeight[60_000])
        assertTrue(Records.newRecords(before, Performance("squat", 60_000, 4))
            .contains("Rep record at this weight"))
        val corrected = Records.forExercise(listOf(Performance("squat", 60_000, 2)), "squat")
        assertEquals(2, corrected.bestRepsByWeight[60_000])
    }

    @Test fun recordTimelineRebuildsAfterHistoricalCorrection() {
        val first = RecordSession(1, 100, listOf(Performance("squat", 60_000, 3),
            Performance("squat", 20_000, 5, warmup = true)))
        val second = RecordSession(2, 200, listOf(Performance("squat", 65_000, 3)))
        val original = Records.history(listOf(first, second))
        assertEquals(RecordKind.BASELINE, original.first().kind)
        assertTrue(original.any { it.sessionId == 2L && it.kind == RecordKind.HEAVIEST })
        val corrected = Records.history(listOf(first.copy(sets = listOf(Performance("squat", 70_000, 3))), second))
        assertTrue(corrected.none { it.sessionId == 2L && it.kind == RecordKind.HEAVIEST })
    }
}
