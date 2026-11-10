package dev.moavia.codriod.linux

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Estimates install progress and time left. Every step has a typical duration on a mid-range phone with ~2 MB/s
 * internet; the real download speed scales the network-heavy steps. Inside a step, real progress comes from apt
 * (APT::Status-Fd), "::progress N" lines in Codriod's scripts, or the download/unpack counters; otherwise the time
 * spent so far is used.
 */
class InstallEstimator(private val clock: () -> Long = System::currentTimeMillis) {
    private val expected = LinkedHashMap<String, Double>()
    private val done = LinkedHashSet<String>()
    private var current: String? = null
    private var currentStart = 0L
    private var explicit = 0f
    private val startedAt = clock()
    /** >1 when the internet is slower than the reference speed. */
    var networkFactor = 1.0
        private set

    fun plan(ids: List<String>) {
        expected.clear(); done.clear(); current = null; explicit = 0f
        ids.forEach { expected[it] = typical(it) }
    }

    fun start(id: String) { current = id; currentStart = clock(); explicit = 0f }
    fun finish(id: String) { done += id; if (current == id) { current = null; explicit = 0f } }
    /** A skipped step takes no time. */
    fun skip(id: String) { expected[id] = 0.0; finish(id) }

    /** Real progress of the running step (0..1); never moves backwards. */
    fun report(id: String, fraction: Float) { if (id == current) explicit = max(explicit, fraction.coerceIn(0f, 1f)) }

    /** Measured download speed in bytes per second. */
    fun measuredSpeed(bytesPerSecond: Double) {
        if (bytesPerSecond > 1000) networkFactor = (REFERENCE_SPEED / bytesPerSecond).coerceIn(0.5, 6.0)
    }

    private fun effective(id: String): Double {
        val base = expected[id] ?: 0.0
        val share = networkShare(id)
        return base * (1 - share + share * networkFactor)
    }

    private fun elapsed(): Double = (clock() - currentStart) / 1000.0

    /** Progress of the running step: real progress if known, else from time. */
    fun stepFraction(): Float {
        val id = current ?: return 0f
        val e = effective(id).coerceAtLeast(1.0)
        val byTime = (0.95 * (1 - exp(-1.2 * elapsed() / e))).toFloat()
        return if (explicit > 0f) max(explicit, min(byTime, explicit + 0.15f)).coerceAtMost(0.99f) else byTime
    }

    fun overall(): Float {
        val total = expected.keys.sumOf(::effective).coerceAtLeast(1.0)
        val finished = done.sumOf(::effective)
        val running = current?.let { effective(it) * stepFraction() } ?: 0.0
        return ((finished + running) / total).toFloat().coerceIn(0f, 1f)
    }

    fun secondsLeft(): Long {
        val id = current
        var left = expected.keys.filter { it !in done && it != id }.sumOf(::effective)
        if (id != null) {
            val e = effective(id)
            val f = stepFraction().toDouble()
            val t = elapsed()
            val fromRate = if (explicit > 0.08f && t > 4) t * (1 - f) / f else Double.NaN
            val fromPlan = max(e - t, e * 0.12 * (1 - f))
            left += if (fromRate.isNaN()) fromPlan else (fromRate + fromPlan) / 2
        }
        return left.roundToLong().coerceAtLeast(0)
    }

    fun elapsedTotalSeconds(): Long = (clock() - startedAt) / 1000

    /** The usual total range for this plan, e.g. "8–15 min". */
    fun usualRange(): String {
        val total = expected.values.sum()
        return "${format(total * 0.7, short = true)}–${format(total * 1.6, short = true)}"
    }

    companion object {
        const val REFERENCE_SPEED = 2.0 * 1024 * 1024

        fun typical(id: String): Double = when (id) {
            "check" -> 2.0; "download" -> 20.0; "extract" -> 45.0; "configure" -> 2.0; "test" -> 6.0
            "base" -> 210.0; "node" -> 90.0
            "cli:claude" -> 110.0; "cli:codex" -> 70.0; "cli:gemini" -> 150.0; "cli:copilot" -> 100.0; "cli:qwen" -> 150.0
            "cli:opencode" -> 80.0; "cli:kilo" -> 130.0; "cli:crush" -> 60.0; "cli:amp" -> 70.0; "cli:gh" -> 45.0
            else -> if (id.startsWith("cli:")) 100.0 else 30.0
        }

        /** Share of a step that depends on download speed. */
        fun networkShare(id: String): Double = when {
            id == "download" -> 0.95
            id == "base" || id == "node" || id.startsWith("cli:") -> 0.6
            else -> 0.0
        }

        /** "about 6 min left", "about 40 sec left", "less than 10 sec left". */
        fun left(seconds: Long): String = when {
            seconds < 10 -> "almost done"
            seconds < 60 -> "about ${((seconds + 9) / 10) * 10} sec left"
            else -> "about ${format(seconds.toDouble())} left"
        }

        fun format(seconds: Double, short: Boolean = false): String {
            val s = seconds.roundToLong()
            return when {
                s < 60 -> "${s} sec"
                s < 3600 -> { val m = (s + 30) / 60; if (short) "$m" + " min" else "$m min" }
                else -> "${s / 3600} h ${(s % 3600) / 60} min"
            }
        }

        fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

        /**
         * Progress hidden in an output line: apt's status lines (download 10–50 %, install 50–100 % of the step) and
         * Codriod's "::progress N" lines. Returns (fraction, message) or null.
         */
        fun parseLine(line: String): Pair<Float, String?>? {
            if (line.startsWith("::progress ")) return (line.removePrefix("::progress ").trim().toFloatOrNull() ?: return null) / 100f to null
            if (line.startsWith("dlstatus:") || line.startsWith("pmstatus:")) {
                val parts = line.split(':', limit = 4)
                if (parts.size < 4) return null
                val pct = parts[2].toFloatOrNull() ?: return null
                val f = if (parts[0] == "dlstatus") 0.10f + 0.40f * pct / 100f else 0.50f + 0.50f * pct / 100f
                return f to parts[3].trim().takeIf { it.isNotEmpty() }
            }
            return null
        }

        /** Lines that only carry progress and are hidden from the log. */
        fun isProgressLine(line: String) = line.startsWith("::progress ") || line.startsWith("dlstatus:") || line.startsWith("pmstatus:") ||
            line.startsWith("media-change:")
    }
}
