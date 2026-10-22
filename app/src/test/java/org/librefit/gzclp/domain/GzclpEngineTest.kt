package org.librefit.gzclp.domain

import org.junit.Assert.*
import org.junit.Test

class GzclpEngineTest {
    private fun progress(tier: Tier, stage: Stage = Stage.FIRST, load: Long = 60_000) =
        LiftProgress("squat", tier, stage, load, 2_500)
    private fun sets(count: Int, reps: Int, load: Long = 60_000) =
        List(count) { CompletedSet(reps, load) }

    @Test fun t1SuccessAndFailures() {
        val first = progress(Tier.T1)
        assertEquals(62_500, GzclpEngine.evaluate(first, sets(5, 3)).next.recommendedGrams)
        val second = GzclpEngine.evaluate(first, sets(5, 2)).next
        assertEquals(Stage.SECOND, second.stage)
        assertEquals(60_000, second.recommendedGrams)
        val third = GzclpEngine.evaluate(second, sets(6, 1)).next
        assertEquals(Stage.THIRD, third.stage)
        assertEquals(60_000, third.recommendedGrams)
        val reset = GzclpEngine.evaluate(third, sets(10, 0)).next
        assertTrue(reset.pendingReset)
        val restarted = GzclpEngine.confirmT1Reset(reset, 100_000).next
        assertEquals(Stage.FIRST, restarted.stage)
        assertEquals(85_000, restarted.recommendedGrams)
    }

    @Test fun t2IsIndependentAndResetsWithConfirmedLoad() {
        val t1 = progress(Tier.T1)
        val t2 = progress(Tier.T2)
        assertEquals(62_500, GzclpEngine.evaluate(t1, sets(5, 3)).next.recommendedGrams)
        val eight = GzclpEngine.evaluate(t2, sets(3, 9)).next
        assertEquals(Stage.SECOND, eight.stage)
        val six = GzclpEngine.evaluate(eight, sets(3, 7)).next
        assertEquals(Stage.THIRD, six.stage)
        val reset = GzclpEngine.evaluate(six, sets(3, 5)).next
        assertTrue(reset.pendingReset)
        val restarted = GzclpEngine.confirmT2Reset(reset, 65_000, WeightUnit.KG).next
        assertEquals(Stage.FIRST, restarted.stage)
        assertEquals(65_000, restarted.cycleStartGrams)
        assertEquals(60_000, t1.recommendedGrams)
    }

    @Test fun t3NeedsCompletedEarlierSetsAndTwentyFiveAmrapReps() {
        val t3 = progress(Tier.T3)
        assertEquals(DecisionStatus.MAINTAIN,
            GzclpEngine.evaluate(t3, listOf(CompletedSet(14, 60_000), CompletedSet(15, 60_000), CompletedSet(25, 60_000))).status)
        assertEquals(DecisionStatus.MAINTAIN, GzclpEngine.evaluate(t3, sets(3, 24)).status)
        assertEquals(DecisionStatus.INCREASE,
            GzclpEngine.evaluate(t3, listOf(CompletedSet(15, 60_000), CompletedSet(15, 60_000), CompletedSet(25, 60_000))).status)
    }

    @Test fun warmupsAndSkippedExerciseDoNotAdvance() {
        val p = progress(Tier.T1)
        assertEquals(DecisionStatus.SKIPPED, GzclpEngine.evaluate(p, listOf(CompletedSet(5, 20_000, isWarmup = true))).status)
        assertEquals(DecisionStatus.SKIPPED, GzclpEngine.evaluate(p, emptyList()).status)
        assertEquals(p, GzclpEngine.evaluate(p, emptyList()).next)
    }

    @Test fun rotationIsIndependentOfTrainingFrequency() {
        assertEquals(listOf(WorkoutDay.A1, WorkoutDay.B1, WorkoutDay.A2, WorkoutDay.B2, WorkoutDay.A1),
            (0..4).map(GzclpRotation::next))
        assertEquals(2, GzclpRotation.week(3, 3))
        assertEquals(1, GzclpRotation.week(3, 4))
    }

    @Test fun unitConversionAndMicroloadingAreStable() {
        val pounds = GzclpWeights.toGrams(100.0, WeightUnit.LB)
        assertEquals(45_359, pounds)
        assertEquals(100.0, GzclpWeights.fromGrams(pounds, WeightUnit.LB), 0.01)
        assertEquals(45_500, GzclpWeights.roundToIncrement(pounds, 500))
    }

    @Test fun unfinishedAndAlreadyAppliedSessionsDoNotAdvance() {
        val exercise = SessionExerciseResult(progress(Tier.T1), sets(5, 3), attempted = true)
        assertFalse(GzclpSessionProgression.evaluate(listOf(exercise), false, false).applied)
        assertFalse(GzclpSessionProgression.evaluate(listOf(exercise), true, true).applied)
        assertEquals(DecisionStatus.INCREASE,
            GzclpSessionProgression.evaluate(listOf(exercise), true, false).decisions.single().status)
    }

    @Test fun skippedExerciseIsNotAFailureAndHistoricalSetsAreImmutableInputs() {
        val original = progress(Tier.T1)
        val skipped = SessionExerciseResult(original, sets(1, 2), attempted = false)
        assertEquals(DecisionStatus.SKIPPED,
            GzclpSessionProgression.evaluate(listOf(skipped), true, false).decisions.single().status)
        val accepted = GzclpEngine.evaluate(original, sets(5, 3))
        val correctedHistoricalSets = sets(5, 2)
        assertEquals(62_500, accepted.next.recommendedGrams)
        assertEquals(DecisionStatus.ADVANCE_STAGE,
            GzclpEngine.evaluate(original, correctedHistoricalSets).status)
        assertEquals(62_500, accepted.next.recommendedGrams)
    }
}
