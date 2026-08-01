package ua.wwind.paging.core

/**
 * The stretch of positions whose access cannot change anything a pager would do.
 *
 * It is the contiguous run of cached positions around [key], pulled in by [margin] on each side so
 * that coming within a page of the edge still moves the window in time; an edge that is also an
 * edge of the data set has nothing beyond it and is kept as is. An empty range - which is what an
 * uncached [key] yields - leaves every access counting, so this is always safe to fall back to.
 *
 * Reading it from the cache rather than from the range that was requested keeps it honest when a
 * source returns less than it was asked for, or when a load failed halfway. The scan is bounded by
 * the cache window and runs once per loading pass, in exchange for `PagingMap.get` inside the
 * returned range costing nothing but the lookup itself - which is what a list scrolling over
 * already loaded rows does on every recomposition.
 */
internal fun <T> PagingMap<T>.settledRangeAround(key: Int, margin: Int): IntRange {
    val lastIndex = size - 1
    val coercedKey = key.coerceIn(0, lastIndex.coerceAtLeast(0))
    if (size <= 0 || !values.containsKey(coercedKey)) return IntRange.EMPTY

    var first = coercedKey
    while (first > 0 && values.containsKey(first - 1)) first--
    var last = coercedKey
    while (last < lastIndex && values.containsKey(last + 1)) last++

    val start = if (first == 0) 0 else first + margin
    val end = if (last == lastIndex) last else last - margin
    return if (start > end) IntRange.EMPTY else start..end
}
