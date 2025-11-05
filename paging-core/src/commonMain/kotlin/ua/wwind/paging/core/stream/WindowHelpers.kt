package ua.wwind.paging.core.stream

internal fun IntRange.intersects(other: IntRange): Boolean {
    return this.first <= other.last && this.last >= other.first
}

internal fun distanceBeyondWindow(window: IntRange, range: IntRange): Int {
    return when {
        window.intersects(range) -> 0
        window.last < range.first -> range.first - window.last
        range.last < window.first -> window.first - range.last
        else -> 0
    }
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
