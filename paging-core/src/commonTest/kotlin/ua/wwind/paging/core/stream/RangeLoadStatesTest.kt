package ua.wwind.paging.core.stream

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import ua.wwind.paging.core.LoadState
import kotlin.test.Test

/**
 * Unit tests for the per-range load state map behind `StreamingPager` (#12).
 *
 * Every message of a portion stream marks its range `Success`, so the common case is a write that
 * changes nothing. Rebuilding the map for it costs an allocation per streamed message and, once the
 * map is fed into a `StateFlow`, a needless equality scan on every one of them.
 */
class RangeLoadStatesTest {

    @Test
    fun a_range_already_in_the_requested_state_leaves_the_map_untouched() {
        val current: Map<IntRange, LoadState> = mapOf(
            0..4 to LoadState.Success,
            5..9 to LoadState.Loading,
        )

        current.withRangeState(0..4, LoadState.Success) shouldBeSameInstanceAs current
    }

    @Test
    fun a_range_in_another_state_is_rewritten() {
        val current: Map<IntRange, LoadState> = mapOf(0..4 to LoadState.Loading)

        current.withRangeState(0..4, LoadState.Success) shouldBe mapOf(0..4 to LoadState.Success)
    }

    @Test
    fun an_unknown_range_is_added() {
        val current: Map<IntRange, LoadState> = mapOf(0..4 to LoadState.Success)

        current.withRangeState(5..9, LoadState.Loading) shouldBe mapOf(
            0..4 to LoadState.Success,
            5..9 to LoadState.Loading,
        )
    }

    @Test
    fun an_absent_map_is_created() {
        val current: Map<IntRange, LoadState>? = null

        current.withRangeState(0..4, LoadState.Loading) shouldBe mapOf(0..4 to LoadState.Loading)
    }
}
