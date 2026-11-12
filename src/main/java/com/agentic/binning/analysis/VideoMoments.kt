package com.agentic.binning.analysis

/** Pure motion scoring and action selection for sampled video frames. */
object VideoMoments {
    /** Preserve the first of similar phrases, and suppress actions if only one distinct moment remains. */
    fun distinct(actions: List<ActionEntry>): List<ActionEntry> {
        val retained = mutableListOf<ActionEntry>()
        val wordSets = mutableListOf<Set<String>>()
        for (action in actions) {
            val words = action.text.split(Regex("[^\\p{L}\\p{N}]+"))
                .map { it.lowercase(java.util.Locale.ROOT) }
                .filter { it.codePointCount(0, it.length) > 3 }
                .map { it.trimEnd('s') }
                .toSet()
            val repeated = wordSets.any { previous ->
                if (previous.isEmpty() || words.isEmpty()) previous == words
                else previous.intersect(words).size * 2 >= maxOf(previous.size, words.size)
            }
            if (!repeated) {
                retained.add(action)
                wordSets.add(words)
            }
        }
        return if (retained.size <= 1) emptyList() else retained
    }

    /** Nearest-second m:ss, with negative times clamped to zero. */
    fun timestamp(timeMs: Long): String {
        val nonnegative = timeMs.coerceAtLeast(0)
        val seconds = nonnegative / 1000 + if (nonnegative % 1000 >= 500) 1 else 0
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
    /** Opening plus up to four interval peaks; quiet clips have no moments. Index zero's score is unused. */
    fun select(changes: List<Double>): List<Int> {
        if (changes.size < 2) return emptyList()
        val peak = changes.drop(1).maxOrNull() ?: return emptyList()
        if (peak < 8.0) return emptyList()
        val floor = maxOf(8.0, peak * 0.4)
        val later = changes.size - 1
        val stretches = minOf(4, later)
        return buildList {
            add(0)
            for (s in 0 until stretches) {
                val from = 1 + later * s / stretches
                val to = 1 + later * (s + 1) / stretches
                var best = from
                for (i in from + 1 until to) {
                    if (changes[i] > changes[best]) best = i
                }
                if (changes[best] >= floor) add(best)
            }
        }
    }
    /** Mean RGB channel difference, omitting pixels whose channels are all below 16 in both frames. */
    fun change(previous: IntArray, current: IntArray): Double {
        var sum = 0L
        var channels = 0L
        for (i in previous.indices) {
            val a = previous[i]
            val b = current[i]
            val ar = (a ushr 16) and 255
            val ag = (a ushr 8) and 255
            val ab = a and 255
            val br = (b ushr 16) and 255
            val bg = (b ushr 8) and 255
            val bb = b and 255
            if (ar < 16 && ag < 16 && ab < 16 && br < 16 && bg < 16 && bb < 16) continue
            sum += kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)
            channels += 3
        }
        return if (channels == 0L) 0.0 else sum.toDouble() / channels
    }
}
