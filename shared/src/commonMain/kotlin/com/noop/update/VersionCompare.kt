package com.noop.update

/**
 * True iff [latest] is a strictly newer version than [current]. Compares dot-separated numeric
 * segments left to right (so `1.40 > 1.39` and `1.9 < 1.10`, both of which a plain string compare
 * gets WRONG). Tolerant of a leading "v" and any non-numeric suffix (e.g. the demo flavour's
 * "1.39-demo", or build metadata). Pure + unit-tested.
 */
object VersionCompare {

    fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }   // stop at "-demo" / build metadata
            .split(".")
            .mapNotNull { it.toIntOrNull() }
}
