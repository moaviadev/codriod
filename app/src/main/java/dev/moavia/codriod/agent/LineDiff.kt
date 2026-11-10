package dev.moavia.codriod.agent

enum class DiffKind { Same, Added, Removed, Gap }

data class DiffLine(val kind: DiffKind, val text: String)

/** Small line diff (LCS) for reviewing agent edits. Large inputs fall back to remove-all/add-all. */
object LineDiff {
    fun diff(old: String, new: String, context: Int = 3, maxCells: Long = 4_000_000): List<DiffLine> {
        val a = if (old.isEmpty()) emptyList() else old.split('\n')
        val b = if (new.isEmpty()) emptyList() else new.split('\n')
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val full = ArrayList<DiffLine>()
        a.subList(0, prefix).forEach { full += DiffLine(DiffKind.Same, it) }
        if (midA.size.toLong() * midB.size > maxCells) {
            midA.forEach { full += DiffLine(DiffKind.Removed, it) }
            midB.forEach { full += DiffLine(DiffKind.Added, it) }
        } else {
            val n = midA.size; val m = midB.size
            val table = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                table[i][j] = if (midA[i] == midB[j]) table[i + 1][j + 1] + 1
                    else maxOf(table[i + 1][j], table[i][j + 1])
            var i = 0; var j = 0
            while (i < n || j < m) when {
                i < n && j < m && midA[i] == midB[j] -> { full += DiffLine(DiffKind.Same, midA[i]); i++; j++ }
                j < m && (i == n || table[i][j + 1] >= table[i + 1][j]) -> { full += DiffLine(DiffKind.Added, midB[j]); j++ }
                else -> { full += DiffLine(DiffKind.Removed, midA[i]); i++ }
            }
        }
        a.subList(a.size - suffix, a.size).forEach { full += DiffLine(DiffKind.Same, it) }
        return collapse(full, context)
    }

    private fun collapse(lines: List<DiffLine>, context: Int): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffKind.Same)
                for (k in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(lines.size - 1)) keep[k] = true
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        lines.forEachIndexed { index, line ->
            if (keep[index]) {
                if (skipped > 0) { out += DiffLine(DiffKind.Gap, "… $skipped unchanged lines"); skipped = 0 }
                out += line
            } else skipped++
        }
        if (skipped > 0 && out.isNotEmpty()) out += DiffLine(DiffKind.Gap, "… $skipped unchanged lines")
        return out
    }

    fun stats(lines: List<DiffLine>) = lines.count { it.kind == DiffKind.Added } to lines.count { it.kind == DiffKind.Removed }
}
