package ua.wwind.paging.sample.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import kotlin.math.max
import kotlin.math.min

/**
 * Adapter that provides normalized scroll position and thumb size for a scrollbar.
 * Values are in [0f, 1f].
 */
interface ScrollbarAdapter {
    /** Current scroll position as a ratio in [0f, 1f]. */
    val positionRatio: Float

    /** Current thumb size as a ratio in (0f, 1f]. */
    val thumbSizeRatio: Float
}

private class LazyListScrollbarAdapter(
    private val state: LazyListState,
) : ScrollbarAdapter {
    override val positionRatio: Float
        get() {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            if (total <= 0) return 0f
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return 0f
            val firstIndex = visible.first().index
            val lastIndex = visible.last().index
            val visibleCount = max(1, lastIndex - firstIndex + 1)
            val maxFirst = max(1, total - visibleCount)
            return min(1f, firstIndex.toFloat() / maxFirst.toFloat())
        }

    override val thumbSizeRatio: Float
        get() {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            if (total <= 0) return 1f
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return 1f
            val firstIndex = visible.first().index
            val lastIndex = visible.last().index
            val visibleCount = max(1, lastIndex - firstIndex + 1)
            val ratio = visibleCount.toFloat() / total.toFloat()
            // Keep the thumb usable with a minimum size
            return ratio.coerceIn(0.05f, 1f)
        }
}

private class ScrollStateScrollbarAdapter(
    private val state: ScrollState,
) : ScrollbarAdapter {
    override val positionRatio: Float
        get() {
            val maxValue = max(1, state.maxValue)
            return state.value.toFloat() / maxValue.toFloat()
        }

    override val thumbSizeRatio: Float
        get() {
            // We don't know viewport/content sizes; use a reasonable default with minimum size.
            return 0.15f
        }
}

@Composable
fun rememberScrollbarAdapter(scrollState: LazyListState): ScrollbarAdapter {
    val adapter = remember(scrollState) { LazyListScrollbarAdapter(scrollState) }
    // Wrap with derived state so reads are efficient when values change
    return remember(scrollState) {
        object : ScrollbarAdapter {
            private val pos = derivedStateOf { adapter.positionRatio }
            private val size = derivedStateOf { adapter.thumbSizeRatio }
            override val positionRatio: Float get() = pos.value
            override val thumbSizeRatio: Float get() = size.value
        }
    }
}

@Composable
fun rememberScrollbarAdapter(scrollState: ScrollState): ScrollbarAdapter {
    val adapter = remember(scrollState) { ScrollStateScrollbarAdapter(scrollState) }
    return remember(scrollState) {
        object : ScrollbarAdapter {
            private val pos = derivedStateOf { adapter.positionRatio }
            private val size = derivedStateOf { adapter.thumbSizeRatio }
            override val positionRatio: Float get() = pos.value
            override val thumbSizeRatio: Float get() = size.value
        }
    }
}
