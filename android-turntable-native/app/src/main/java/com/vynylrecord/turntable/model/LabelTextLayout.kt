package com.vynylrecord.turntable.model

/**
 * Text layout for the printed record label.
 *
 * Deliberately free of `android.graphics`: the caller passes a measuring function (which is
 * `Paint::measureText` on device and a stub in unit tests), so truncation and wrapping behaviour is
 * testable on the JVM and identical no matter which font ends up loaded.
 */
object LabelTextLayout {

    const val ELLIPSIS = "\u2026"

    /**
     * Truncates [text] to fit [maxWidth], appending an ellipsis.
     *
     * The longest prefix that still fits *with* the ellipsis is found by binary search, so cost is
     * logarithmic in the character count instead of linear. Returns the ellipsis alone when not
     * even a single character fits.
     */
    fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (measure(trimmed) <= maxWidth) return trimmed
        if (maxWidth <= 0f) return ELLIPSIS

        var low = 0
        var high = trimmed.length
        var best = 0
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = trimmed.take(mid).trimEnd() + ELLIPSIS
            if (measure(candidate) <= maxWidth) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (best <= 0) return ELLIPSIS
        return trimmed.take(best).trimEnd() + ELLIPSIS
    }

    /**
     * Greedy word wrap limited to [maxLines].
     *
     * Words that cannot fit on a line on their own are hard-broken first, so the wrapper never has
     * to reason about overflow mid-line. If content still does not fit in [maxLines], the final line
     * is rebuilt from everything that remains and ellipsized: a label must never show clipped text
     * without an ellipsis.
     */
    fun wrap(text: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        if (maxLines <= 0 || maxWidth <= 0f) return emptyList()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        // Hard-break over-long words into pieces that each fit on their own line.
        val pieces = ArrayList<String>(words.size * 2)
        for (word in words) {
            var remainder = word
            while (remainder.length > 1 && measure(remainder) > maxWidth) {
                var fit = 1
                while (fit < remainder.length && measure(remainder.take(fit + 1)) <= maxWidth) fit++
                pieces.add(remainder.take(fit))
                remainder = remainder.drop(fit)
            }
            if (remainder.isNotEmpty()) pieces.add(remainder)
        }

        val lines = ArrayList<String>(maxLines)
        var index = 0
        while (index < pieces.size && lines.size < maxLines) {
            var line = pieces[index]
            index++
            while (index < pieces.size) {
                val candidate = "$line ${pieces[index]}"
                if (measure(candidate) <= maxWidth) {
                    line = candidate
                    index++
                } else {
                    break
                }
            }
            lines.add(line)
        }

        if (index < pieces.size && lines.isNotEmpty()) {
            val remainder = pieces.drop(index).joinToString(" ")
            val lastIndex = lines.size - 1
            lines[lastIndex] = ellipsize("${lines[lastIndex]} $remainder", maxWidth, measure)
        }
        return lines
    }

    /**
     * Re-balances two adjacent lines so the title block looks deliberate rather than ragged: moves
     * trailing words down while the receiving line still has room and the giving line keeps content.
     */
    fun balance(lines: List<String>, maxWidth: Float, measure: (String) -> Float): List<String> {
        if (lines.size < 2) return lines
        val working = lines.toMutableList()
        var changed = true
        var guard = 0
        while (changed && guard < 8) {
            changed = false
            guard++
            for (i in 0 until working.size - 1) {
                val current = working[i]
                val next = working[i + 1]
                if (measure(current) <= measure(next)) continue
                val words = current.split(" ").filter { it.isNotEmpty() }
                if (words.size < 2) continue
                val moved = words.last()
                val candidateNext = if (next.isBlank()) moved else "$moved $next"
                if (measure(candidateNext) > maxWidth) continue
                working[i] = words.dropLast(1).joinToString(" ")
                working[i + 1] = candidateNext
                changed = true
            }
        }
        return working
    }

    /** Wrap + balance, the combination actually used for the label title. */
    fun layoutTitle(title: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        val wrapped = wrap(title, maxWidth, maxLines, measure)
        return if (wrapped.size == 2) balance(wrapped, maxWidth, measure) else wrapped
    }
}
