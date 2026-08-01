package ua.wwind.paging.core

/**
 * Computes contiguous missing subranges inside [expected] based on the set of present absolute positions.
 *
 * Every gap is reported, so a fragmented cache is described exactly instead of collapsing into a
 * single "longest run" that hides everything on the other side of a hole.
 *
 * Example: expected = 10..15, present = {10, 12, 15} -> missing: 11..11, 13..14
 *
 * @param expected Inclusive expected range.
 * @param presentKeys Absolute positions that are present locally.
 * @return Missing contiguous ranges in ascending order.
 */
internal fun computeMissingRanges(expected: IntRange, presentKeys: Set<Int>): List<IntRange> {
    if (expected.isEmpty()) return emptyList()
    val missing = mutableListOf<IntRange>()
    var start: Int? = null
    for (key in expected.first..expected.last) {
        val exists = key in presentKeys
        if (!exists && start == null) start = key
        if ((exists || key == expected.last) && start != null) {
            val endExclusive = if (exists) key else key + 1
            val end = endExclusive - 1
            if (end >= start) missing.add(start..end)
            start = null
        }
    }
    return missing
}
