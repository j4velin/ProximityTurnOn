package de.j4velin.smarthome.proximityturnon

import kotlin.math.abs

/**
 * Detects a person casting a shadow onto the light sensor: a sudden drop of the
 * lux value below a slowly moving baseline.
 *
 * The baseline is an exponential moving average of the light level, so gradual
 * changes (sunset, clouds, dimming) are followed. A reading counts as a shadow
 * when it drops below the baseline by both the configured percentage and by
 * clearly more than the sensor noise. While a shadow is detected the baseline is
 * frozen, so a person standing still keeps counting as "present" instead of
 * slowly becoming the new normal.
 *
 * During a warm-up period after the first reading nothing counts as a shadow
 * and baseline and noise are always learned. Without it a noisy sensor would
 * never get a noise estimate, because its downward jitter would be classified
 * as shadows and excluded from the estimate.
 *
 * Pure Kotlin, no Android dependencies - see the unit tests.
 */
class ShadowDetector(
    /** Percentage the light level must drop below the baseline */
    var dropPercent: Int,
    /** Baseline follows the light level with this time constant */
    private val baselineTauMs: Long = 30_000L,
    /** Noise follows the deviation from the baseline with this time constant */
    private val noiseTauMs: Long = 10_000L,
    /** A drop must exceed the noise by this factor, whatever the configured percentage */
    private val noiseMargin: Float = 4f,
    /** Below this baseline the room is too dark for shadow detection */
    private val minBaselineLux: Float = 5f,
    /** No shadows are reported for this long after the first reading */
    private val warmupMs: Long = 10_000L,
) {
    var baseline: Float? = null
        private set

    /** Running estimate of the sensor noise (mean absolute deviation from the baseline) */
    var noise: Float = 0f
        private set

    private var warmupUntil = 0L
    private var lastUpdate = 0L

    /**
     * Forgets the baseline so the next reading becomes the new one, keeping the
     * noise estimate. Used when the light situation changes for a known reason,
     * e.g. the display turning off (its light leaks into the sensor). [warmupMs]
     * overrides the regular warm-up for this reset.
     */
    fun reset(warmupMs: Long = this.warmupMs) {
        baseline = null
        pendingWarmupMs = warmupMs
    }

    private var pendingWarmupMs = warmupMs

    /** Lux value below which the next reading counts as a shadow */
    val triggerLux: Float
        get() {
            val base = baseline ?: return 0f
            return base - maxOf(base * dropPercent / 100f, noise * noiseMargin)
        }

    /** Feeds a sensor reading, returns true if it is a shadow */
    fun update(lux: Float, nowMs: Long): Boolean {
        val base = baseline
        if (base == null) {
            baseline = lux
            warmupUntil = nowMs + pendingWarmupMs
            pendingWarmupMs = warmupMs
            lastUpdate = nowMs
            return false
        }
        val drop = base - lux
        val threshold = maxOf(base * dropPercent / 100f, noise * noiseMargin)
        val warmingUp = nowMs < warmupUntil
        val shadow = !warmingUp && base >= minBaselineLux && drop > threshold
        if (!shadow) {
            val dt = (nowMs - lastUpdate).coerceAtLeast(1).toFloat()
            baseline = base + (dt / baselineTauMs).coerceIn(0f, 1f) * (lux - base)
            noise += (dt / noiseTauMs).coerceIn(0f, 1f) * (abs(drop) - noise)
        }
        lastUpdate = nowMs
        return shadow
    }
}
