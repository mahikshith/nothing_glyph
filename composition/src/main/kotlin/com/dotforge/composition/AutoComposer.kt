package com.dotforge.composition

import com.dotforge.device.DeviceProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Looks for lights composed from a song. */
enum class ComposeStyle(val label: String, val hint: String) {
    PULSE("Pulse", "The whole matrix breathes with the beat"),
    SPECTRUM("Spectrum", "Bars for bass in the middle, treble outside"),
    RIPPLE("Ripple", "Each hit sends a ring outwards"),
    SPARKLE("Sparkle", "Bright notes light random dots"),
}

/**
 * Turns an [AudioAnalysis] into Phone (3) matrix rows (cellCount values 0-4095, 60 fps). Every cell follows
 * an attack/decay envelope, so lights rise fast and fall smoothly. Cells without an LED stay 0. The same input,
 * style, settings and [seed] always give the same rows.
 *
 * [intensity] scales brightness (1 = normal, 0.25..2). [speed] scales how fast lights fade and ripples travel
 * (1 = normal, 0.5..2).
 */
object AutoComposer {
    fun compose(
        a: AudioAnalysis,
        style: ComposeStyle,
        profile: DeviceProfile = DeviceProfile.Phone3,
        intensity: Float = 1f,
        speed: Float = 1f,
        seed: Int = 7,
    ): List<IntArray> {
        val g = Geometry(profile)
        val env = FloatArray(profile.cellCount)
        val target = FloatArray(profile.cellCount)
        val dt = 1.0 / a.fps
        // per-frame decay factor: fades to ~37% in 0.2 s at speed 1
        val decay = exp(-speed * dt / 0.2).toFloat()
        val attack = 0.65f
        val random = Random(seed)
        val ripples = ArrayList<FloatArray>() // [radius, strength]
        val out = ArrayList<IntArray>(a.frameCount)
        for (i in 0 until a.frameCount) {
            target.fill(0f)
            when (style) {
                ComposeStyle.PULSE -> {
                    val level = max(a.bass(i) * 0.85f, max(a.onset[i], a.bassOnset[i]))
                    for (cell in g.leds) target[cell] = level * (1f - 0.45f * g.radius[cell] / g.maxR)
                }
                ComposeStyle.SPECTRUM -> {
                    val c = g.centre
                    for (cell in g.leds) {
                        val x = cell % g.size
                        val y = cell / g.size
                        // bass bands in the middle column, treble at both sides
                        val pos = abs(x - c).toFloat() / c * (a.bandCount - 1)
                        val b0 = pos.toInt().coerceAtMost(a.bandCount - 1)
                        val b1 = (b0 + 1).coerceAtMost(a.bandCount - 1)
                        val t = pos - b0
                        val e = a.bands[i][b0] * (1 - t) + a.bands[i][b1] * t
                        val h = e * (c + 0.5f)
                        val d = abs(y - c).toFloat()
                        // quiet bands fade out instead of leaving a dot on the centre line
                        target[cell] = ((h - d) + 0.5f).coerceIn(0f, 1f) * (0.55f + 0.45f * e) * (e * 8f).coerceAtMost(1f)
                    }
                }
                ComposeStyle.RIPPLE -> {
                    val hit = max(a.onset[i], a.bassOnset[i])
                    if (hit > 0f) ripples += floatArrayOf(0f, 0.4f + 0.6f * hit)
                    val glow = a.bass(i) * 0.35f
                    for (cell in g.leds) if (g.radius[cell] < 2.5f) target[cell] = glow
                    val iter = ripples.iterator()
                    while (iter.hasNext()) {
                        val r = iter.next()
                        val fade = (1f - r[0] / (g.maxR + 2f)).coerceAtLeast(0f)
                        for (cell in g.leds) {
                            val v = (1f - abs(g.radius[cell] - r[0]) / 1.3f).coerceAtLeast(0f) * r[1] * fade
                            if (v > target[cell]) target[cell] = v
                        }
                        r[0] += 0.22f * speed
                        if (r[0] > g.maxR + 2f) iter.remove()
                    }
                    if (ripples.size > 8) ripples.removeAt(0)
                }
                ComposeStyle.SPARKLE -> {
                    val hit = max(a.highOnset[i], a.onset[i] * 0.6f)
                    var count = (a.treble(i) * 2.5f).toInt()
                    if (hit > 0f) count += 6 + (hit * 30).toInt()
                    repeat(count) {
                        val cell = g.leds[random.nextInt(g.leds.size)]
                        target[cell] = max(target[cell], 0.5f + 0.5f * random.nextFloat())
                    }
                }
            }
            val row = IntArray(profile.cellCount)
            for (cell in g.leds) {
                val t = target[cell]
                val e = if (t > env[cell]) env[cell] + (t - env[cell]) * attack else env[cell] * decay
                env[cell] = if (e < 0.004f) 0f else e
                row[cell] = (env[cell] * intensity * GlyphAuthor.MAX).toInt().coerceIn(0, GlyphAuthor.MAX)
            }
            out += row
        }
        return out
    }

    private class Geometry(p: DeviceProfile) {
        val size = p.size
        val centre = (p.size - 1) / 2
        val leds: IntArray = p.ledCells
        val maxR = sqrt(p.radiusSquared.toFloat())
        val radius = FloatArray(p.cellCount) { cell ->
            val dx = cell % p.size - centre
            val dy = cell / p.size - centre
            sqrt((dx * dx + dy * dy).toFloat())
        }
    }
}
