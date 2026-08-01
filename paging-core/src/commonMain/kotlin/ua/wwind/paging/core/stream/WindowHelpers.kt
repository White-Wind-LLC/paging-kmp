package ua.wwind.paging.core.stream

import kotlin.math.abs

internal fun IntRange.intersects(other: IntRange): Boolean = this.first <= other.last && this.last >= other.first

internal fun distanceBeyondWindow(window: IntRange, range: IntRange): Int = when {
    window.intersects(range) -> 0
    window.last < range.first -> range.first - window.last
    range.last < window.first -> window.first - range.last
    else -> 0
}

internal fun computeWindowForKeyAligned(key: Int, totalSize: Int, config: StreamingPagerConfig): IntRange {
    val full = 0..<totalSize.coerceAtLeast(1)
    val centered = key.coerceIn(full)
    val start = (centered - config.preloadSize).coerceAtLeast(full.first)
    val end = (centered + config.preloadSize).coerceAtMost(full.last)
    return start..end
}

internal fun computeWindowAroundCenter(centerChunk: IntRange, totalSize: Int, config: StreamingPagerConfig): IntRange {
    val full = 0..<totalSize.coerceAtLeast(1)
    val start = (centerChunk.first - config.preloadSize).coerceAtLeast(full.first)
    val end = (centerChunk.last + config.preloadSize).coerceAtMost(full.last)
    return start..end
}

internal fun alignedChunkStartForKey(key: Int, baseStart: Int, config: StreamingPagerConfig): Int {
    val diff = key - baseStart
    val steps = kotlin.math.floor(diff.toDouble() / config.loadSize).toInt()
    return baseStart + steps * config.loadSize
}

internal fun alignedChunkContaining(key: Int, baseStart: Int, totalSize: Int, config: StreamingPagerConfig): IntRange {
    val start = alignedChunkStartForKey(key, baseStart, config).coerceAtLeast(0)
    val end = (start + config.loadSize).coerceAtMost(totalSize.coerceAtLeast(1))
    return start..<end
}

/**
 * Chunks that should be streaming for a key, together with the window they were computed for.
 *
 * [window] is null while the total size is still unknown: nothing has been placed around a real
 * position yet, so no running stream can be out of date either.
 */
internal data class StreamWindowPlan(val window: IntRange?, val chunks: List<IntRange>)

/**
 * Works out which ranges should be streaming for [key].
 *
 * The chunk grid is aligned to whichever of [activeRanges] still overlaps the window around [key],
 * so that a viewport moving by a few rows keeps its existing streams instead of re-cutting the
 * whole window at a new offset.
 *
 * [key] is clamped into `0..<totalSize` first: an out-of-bounds key - the total shrinking below the
 * viewport, or a transient read past the end - would otherwise align onto a chunk starting at or
 * beyond the last index, and [alignedChunkContaining] would hand back an empty centre chunk.
 */
internal fun planStreamWindow(
    activeRanges: Set<IntRange>,
    key: Int,
    totalSize: Int,
    config: StreamingPagerConfig,
): StreamWindowPlan {
    if (totalSize == 0) return StreamWindowPlan(window = null, chunks = listOf(0..<config.loadSize))

    val safeKey = key.coerceIn(0, totalSize - 1)
    val windowForKeyAligned = computeWindowForKeyAligned(safeKey, totalSize, config)
    val keepers = activeRanges.filter { it.intersects(windowForKeyAligned) }
    val baseStart = keepers.minByOrNull { abs(it.first - safeKey) }?.first
        ?: alignedChunkStartForKey(safeKey, baseStart = 0, config)

    val centerChunk = alignedChunkContaining(safeKey, baseStart, totalSize, config)
    val window = computeWindowAroundCenter(centerChunk, totalSize, config)

    return StreamWindowPlan(window, chunksAcross(centerChunk, window, totalSize, config))
}

/**
 * Tiles [window] with load-size chunks aligned to [centerChunk], ordered from the lowest position
 * to the highest.
 */
internal fun chunksAcross(
    centerChunk: IntRange,
    window: IntRange,
    totalSize: Int,
    config: StreamingPagerConfig,
): List<IntRange> {
    val forward = buildList {
        var start = centerChunk.first + config.loadSize
        while (start <= window.last) {
            val end = (start + config.loadSize - 1).coerceAtMost(totalSize - 1)
            add(start..end)
            start += config.loadSize
        }
    }

    val backward = buildList {
        var start = centerChunk.first - config.loadSize
        while (start + config.loadSize - 1 >= window.first && start >= 0) {
            val end = (start + config.loadSize - 1).coerceAtMost(totalSize - 1)
            add(start..end)
            start -= config.loadSize
        }
    }.reversed()

    return backward + listOf(centerChunk) + forward
}

/**
 * Sort key that opens the ranges closest to [anchor] first, and pushes the ones lying against the
 * scroll direction behind every range that follows it.
 */
internal fun openOrderKey(range: IntRange, anchor: IntRange, directionForward: Boolean): Int {
    val delta = range.first - anchor.first
    val alongDirection = if (directionForward) delta >= 0 else delta <= 0
    return if (alongDirection) abs(delta) else Int.MAX_VALUE / 2 + abs(delta)
}
