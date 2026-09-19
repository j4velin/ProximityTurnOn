package de.j4velin.smarthome.proximityturnon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowDetectorTest {

    private companion object {
        /** Sensor reports about every 200 ms, like the tablet's ALS */
        const val STEP_MS = 200L
        const val ROOM_LUX = 380f
    }

    private var now = 0L

    /** Feeds [samples] readings of [lux], returns whether the last one was a shadow */
    private fun ShadowDetector.feed(lux: Float, samples: Int = 1): Boolean {
        var shadow = false
        repeat(samples) {
            now += STEP_MS
            shadow = update(lux, now)
        }
        return shadow
    }

    /** A detector that has settled on [ROOM_LUX] with negligible noise */
    private fun settled(dropPercent: Int = 15) = ShadowDetector(dropPercent).apply { feed(ROOM_LUX, samples = 500) }

    @Test
    fun `first reading only initialises the baseline`() {
        val detector = ShadowDetector(dropPercent = 15)
        assertFalse(detector.feed(ROOM_LUX))
        assertEquals(ROOM_LUX, detector.baseline!!, 0f)
        assertEquals(0f, detector.noise, 0f)
    }

    @Test
    fun `constant light never triggers`() {
        val detector = ShadowDetector(dropPercent = 15)
        repeat(1000) { assertFalse(detector.feed(ROOM_LUX)) }
        assertEquals(ROOM_LUX, detector.baseline!!, 0.01f)
    }

    @Test
    fun `sudden drop beyond the percentage is a shadow`() {
        val detector = settled(dropPercent = 15)
        assertTrue(detector.feed(ROOM_LUX * 0.8f))
    }

    @Test
    fun `drop within the percentage is not a shadow`() {
        val detector = settled(dropPercent = 15)
        assertFalse(detector.feed(ROOM_LUX * 0.9f))
    }

    @Test
    fun `trigger level reflects the percentage`() {
        val detector = settled(dropPercent = 20)
        assertEquals(ROOM_LUX * 0.8f, detector.triggerLux, 0.5f)
        // just above the level: no shadow, just below: shadow
        assertFalse(detector.feed(detector.triggerLux + 1f))
        assertTrue(detector.feed(detector.triggerLux - 1f))
    }

    @Test
    fun `changing the percentage takes effect immediately`() {
        val detector = settled(dropPercent = 30)
        assertFalse(detector.feed(ROOM_LUX * 0.8f))
        detector.dropPercent = 10
        assertTrue(detector.feed(ROOM_LUX * 0.8f))
    }

    @Test
    fun `baseline is frozen while the shadow persists`() {
        val detector = settled()
        // someone stands in front of the tablet for two minutes
        repeat(600) { assertTrue(detector.feed(ROOM_LUX * 0.6f)) }
        assertEquals(ROOM_LUX, detector.baseline!!, 0.01f)
    }

    @Test
    fun `baseline follows once the shadow is gone`() {
        val detector = settled()
        detector.feed(ROOM_LUX * 0.6f, samples = 50)
        detector.feed(ROOM_LUX, samples = 500)
        assertEquals(ROOM_LUX, detector.baseline!!, 0.01f)
        assertTrue(detector.feed(ROOM_LUX * 0.6f))
    }

    @Test
    fun `slow decline like a sunset is followed without triggering`() {
        val detector = settled()
        // 380 lx down to 20 lx over ten minutes
        val steps = 10 * 60 * 1000 / STEP_MS.toInt()
        for (i in 1..steps) {
            val lux = ROOM_LUX - (ROOM_LUX - 20f) * i / steps
            assertFalse("triggered at step $i (${lux} lx)", detector.feed(lux))
        }
        // an EMA lags a linear ramp by tau * slope = 30 s * 0.6 lx/s = 18 lx
        assertEquals(38f, detector.baseline!!, 2f)
    }

    @Test
    fun `no shadow during warm-up, baseline and noise are learned instead`() {
        val detector = ShadowDetector(dropPercent = 15, warmupMs = 10_000L)
        detector.feed(ROOM_LUX)
        // 5 s in: a drop that would otherwise be a shadow
        detector.feed(ROOM_LUX, samples = 24)
        assertFalse(detector.feed(ROOM_LUX * 0.5f))
        assertTrue("baseline should have followed the drop", detector.baseline!! < ROOM_LUX)
        assertTrue("noise should have picked up the deviation", detector.noise > 0f)
        // after the warm-up the same drop is a shadow
        detector.feed(ROOM_LUX, samples = 500)
        assertTrue(detector.feed(ROOM_LUX * 0.5f))
    }

    @Test
    fun `a stepwise change is a shadow only until the baseline caught up`() {
        val detector = settled()
        // lights dimmed to 50%: first a shadow ...
        assertTrue(detector.feed(ROOM_LUX * 0.5f))
        // ... but the frozen baseline means it stays a shadow, as designed:
        // a person standing still must not become the new normal
        assertTrue(detector.feed(ROOM_LUX * 0.5f, samples = 100))
    }

    @Test
    fun `too dark rooms never trigger`() {
        val detector = ShadowDetector(dropPercent = 15, minBaselineLux = 5f)
        detector.feed(4f, samples = 100)
        assertFalse(detector.feed(0f))
    }

    @Test
    fun `noise guard overrides a tiny percentage on a noisy sensor`() {
        val detector = ShadowDetector(dropPercent = 2, noiseMargin = 4f)
        // readings jitter by +-10 lx around the room level
        repeat(500) { i -> detector.feed(ROOM_LUX + if (i % 2 == 0) 10f else -10f) }
        val noise = detector.noise
        assertTrue("noise estimate should be several lx, was $noise", noise > 5f)
        // 2% of 380 = 7.6 lx would trigger on every second sample without the guard
        assertFalse(detector.feed(ROOM_LUX - 10f))
        assertTrue(detector.triggerLux < ROOM_LUX - 4f * noise + 1f)
        // a real shadow well beyond the noise still triggers
        assertTrue(detector.feed(ROOM_LUX - 6f * noise))
    }

    @Test
    fun `noise estimate stays near zero on a clean signal`() {
        val detector = settled()
        assertTrue(detector.noise < 0.01f)
        assertEquals(ROOM_LUX * 0.85f, detector.triggerLux, 0.5f)
    }

    @Test
    fun `long gaps between readings do not overshoot the baseline`() {
        val detector = settled()
        // ten minutes without an event (on-change sensor, constant light), then a small change
        now += 10 * 60 * 1000
        assertFalse(detector.update(ROOM_LUX - 10f, now))
        // alpha is clamped to 1, so the baseline jumps exactly to the reading, not past it
        assertEquals(ROOM_LUX - 10f, detector.baseline!!, 0.01f)
    }
}
