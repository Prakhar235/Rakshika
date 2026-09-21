package com.rakshika.app.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEquationTest {

    @Test
    fun seedAtNeutralValuesScoresTheBase() {
        val neutral = mapOf("avg_speed_kmh" to 25.0, "has_named_road" to 0.5, "turns_per_km" to 3.0)
        assertEquals(50, SafetyEquation.SEED.evaluate(neutral).score)
    }

    @Test
    fun fastNamedDirectRouteScoresHigherThanSlowUnnamedWindingOne() {
        val good = mapOf("avg_speed_kmh" to 40.0, "has_named_road" to 1.0, "turns_per_km" to 1.0)
        val bad = mapOf("avg_speed_kmh" to 8.0, "has_named_road" to 0.0, "turns_per_km" to 7.0)
        val goodScore = SafetyEquation.SEED.evaluate(good).score
        val badScore = SafetyEquation.SEED.evaluate(bad).score
        assertTrue("good=$goodScore bad=$badScore", goodScore > 70 && badScore < 30)
    }

    @Test
    fun eachTermIsCappedSoOneSignalCannotSwampTheRest() {
        val term = EquationTerm("avg_speed_kmh", weight = 10.0, reference = 0.0, cap = 5.0)
        assertEquals(5.0, term.points(1000.0), 0.0)
        assertEquals(-5.0, term.points(-1000.0), 0.0)
    }

    @Test
    fun scoreIsClampedToOneThroughNinetyNine() {
        val high = SafetyEquation(90.0, listOf(EquationTerm("lit_fraction", 100.0, 0.0, 40.0)))
        val low = SafetyEquation(5.0, listOf(EquationTerm("lit_fraction", -100.0, 0.0, 40.0)))
        assertEquals(99, high.evaluate(mapOf("lit_fraction" to 1.0)).score)
        assertEquals(1, low.evaluate(mapOf("lit_fraction" to 1.0)).score)
    }

    @Test
    fun anUnmeasuredFeatureIsSkippedAndReportedNotTreatedAsZero() {
        // If a missing lit_fraction were read as 0 ("unlit"), this would lose 30 points.
        val eq = SafetyEquation(50.0, listOf(EquationTerm("lit_fraction", 60.0, 0.5, 30.0)))
        val result = eq.evaluate(emptyMap())
        assertEquals(50, result.score)
        assertEquals(listOf("lit_fraction"), result.unmeasured)
        assertTrue(result.contributions.isEmpty())
    }

    @Test
    fun riderStarsMapOntoTheScoreScale() {
        val stars = (1..5).map { TripFeedback(it, emptyMap(), 0L).actualScore }
        assertEquals(listOf(10, 30, 50, 70, 90), stars)
    }

    @Test
    fun confidenceIsCappedByHowManyTripsRidersHaveRated() {
        assertEquals(0.35, EvidenceCap.forRatedTrips(0), 1e-9)
        assertEquals(0.65, EvidenceCap.forRatedTrips(5), 1e-9)
        assertEquals(0.95, EvidenceCap.forRatedTrips(100), 1e-9)
    }

    @Test
    fun everyFeatureTheSeedUsesIsInTheCatalog() {
        SafetyEquation.SEED.terms.forEach { assertTrue(it.feature, FeatureCatalog.get(it.feature) != null) }
    }
}
