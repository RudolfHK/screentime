package io.github.rudolfhk.screentime.core

import kotlin.math.abs
import kotlin.math.roundToInt

object Format {

    /** Compact duration: "3h 12m", "45m", "38s", "0m". */
    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> "${m}m"
            s > 0 -> "${s}s"
            else -> "0m"
        }
    }

    /** Duration with seconds precision for short values: "1m 05s". */
    fun durationPrecise(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }

    /** Minutes after midnight → "07:42". Values ≥ 24h wrap. */
    fun minuteOfDay(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** Relative change between [current] and [previous] as "+12%" / "−8%", or null if undefined. */
    fun delta(current: Long, previous: Long): String? {
        if (previous <= 0) return null
        val pct = ((current - previous) * 100.0 / previous).roundToInt()
        return when {
            pct > 0 -> "+$pct%"
            pct < 0 -> "−${abs(pct)}%"
            else -> "±0%"
        }
    }

    fun percent(ratio: Double): String = "${(ratio * 100).roundToInt()}%"
}
