package dev.droidprobe.okhttp

/** `*` matches within one path segment, `**` as a whole segment matches any number of segments. */
internal object PathGlob {
    fun matches(pattern: String, path: String): Boolean =
        matchSegments(segments(pattern), 0, segments(path), 0)

    private fun segments(value: String): List<String> = value.removePrefix("/").split('/')

    private fun matchSegments(pattern: List<String>, i: Int, path: List<String>, j: Int): Boolean {
        if (i == pattern.size) return j == path.size
        if (pattern[i] == "**") return (j..path.size).any { matchSegments(pattern, i + 1, path, it) }
        if (j == path.size) return false
        return segmentRegex(pattern[i]).matches(path[j]) && matchSegments(pattern, i + 1, path, j + 1)
    }

    private fun segmentRegex(segment: String): Regex =
        Regex(segment.split('*').joinToString("[^/]*") { Regex.escape(it) })
}
