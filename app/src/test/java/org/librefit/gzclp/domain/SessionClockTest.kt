package org.librefit.gzclp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionClockTest {
    @Test fun pausedDaysDoNotCountTowardActiveDuration() {
        val start = 1_000_000L
        val paused = SessionClock(0, start).pause(start + 30 * 60_000L)
        assertEquals(30 * 60_000L, paused.elapsed(start + 4 * 86_400_000L))
        val resumed = paused.resume(start + 4 * 86_400_000L)
        assertEquals(45 * 60_000L, resumed.elapsed(start + 4 * 86_400_000L + 15 * 60_000L))
    }

    @Test fun legacyDurationStaysUnknownUntilUserResolvesIt() {
        val legacy = SessionClock(null, null)
        assertNull(legacy.elapsed(100_000))
        assertEquals(12 * 60_000L, legacy.resolve(12).elapsed(100_000))
    }
}
