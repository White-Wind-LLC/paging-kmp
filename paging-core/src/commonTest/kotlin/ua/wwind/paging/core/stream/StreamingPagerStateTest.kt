package ua.wwind.paging.core.stream

import co.touchlab.kermit.Logger
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.core.PagingMap
import kotlin.test.Test

/**
 * Tests for the load states `StreamingPager` derives from its per-range states (#11, #12).
 *
 * `loadState` follows the range holding the anchor key, `prefetchState` aggregates the others. The
 * per-range map changes on every streamed message, so both flows must drop repeats.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPagerStateTest {

    /** With [totalKnown], `readTotal()` has already reported a non-empty list. */
    private fun state(totalKnown: Boolean = true) = StreamingPagerState<Int>(
        config = StreamingPagerConfig(loadSize = 5, preloadSize = 5, cacheSize = 100, keyDebounceMs = 0),
        readPortion = { _, _ -> emptyFlow() },
        logger = Logger,
    ).apply {
        if (totalKnown) {
            data.value = PagingMap(20, persistentMapOf()) {}
            totalState.value = LoadState.Success
        }
    }

    private val boom = IllegalStateException("boom")

    @Test
    fun initial_states_are_loading_and_success() = runTest {
        val state = state(totalKnown = false)

        state.loadStateFlow.first() shouldBe LoadState.Loading
        state.prefetchStateFlow.first() shouldBe LoadState.Success
    }

    @Test
    fun total_error_is_reported_in_load_state_only_and_wins_over_the_ranges() = runTest {
        val state = state()
        state.anchorKey.value = 2
        state.data.value = PagingMap(20, persistentMapOf(2 to 2)) {}
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Success, 5..9 to LoadState.Success)
        state.totalState.value = LoadState.Error(boom, 0)

        state.loadStateFlow.first() shouldBe LoadState.Error(boom, 2)
        state.prefetchStateFlow.first() shouldBe LoadState.Success
    }

    @Test
    fun total_loading_is_loading_over_a_missing_item_and_success_over_a_cached_one() = runTest {
        val state = state()
        state.anchorKey.value = 2
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Success)
        state.totalState.value = LoadState.Loading

        state.loadStateFlow.first() shouldBe LoadState.Loading

        state.data.value = PagingMap(20, persistentMapOf(2 to 2)) {}

        state.loadStateFlow.first() shouldBe LoadState.Success
    }

    @Test
    fun range_error_shows_while_the_total_is_still_loading() = runTest {
        val state = state(totalKnown = false)
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Error(boom, 0))

        state.loadStateFlow.first() shouldBe LoadState.Error(boom, 0)
    }

    @Test
    fun known_empty_total_is_success_whatever_the_ranges_say() = runTest {
        val state = state(totalKnown = false)
        state.totalState.value = LoadState.Success

        state.loadStateFlow.first() shouldBe LoadState.Success

        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Loading)

        state.loadStateFlow.first() shouldBe LoadState.Success
    }

    @Test
    fun load_state_follows_only_the_anchor_range() = runTest {
        val state = state()
        state.anchorKey.value = 2
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Success, 5..9 to LoadState.Loading)

        state.loadStateFlow.first() shouldBe LoadState.Success
        state.prefetchStateFlow.first() shouldBe LoadState.Loading
    }

    @Test
    fun anchor_loading_over_missing_item_is_loading() = runTest {
        val state = state()
        state.anchorKey.value = 2
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Loading)

        state.loadStateFlow.first() shouldBe LoadState.Loading
    }

    @Test
    fun anchor_loading_over_cached_item_is_success() = runTest {
        val state = state()
        state.anchorKey.value = 2
        state.data.value = PagingMap(10, persistentMapOf(2 to 2)) {}
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Loading)

        state.loadStateFlow.first() shouldBe LoadState.Success
    }

    @Test
    fun errors_carry_the_anchor_key() = runTest {
        val state = state()
        state.anchorKey.value = 7
        state.rangeLoadStates.value = mapOf(5..9 to LoadState.Error(boom, 5))

        state.loadStateFlow.first() shouldBe LoadState.Error(boom, 7)

        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Error(boom, 0), 5..9 to LoadState.Success)

        state.loadStateFlow.first() shouldBe LoadState.Success
        state.prefetchStateFlow.first() shouldBe LoadState.Error(boom, 7)
    }

    @Test
    fun prefetch_loading_outranks_error() = runTest {
        val state = state()
        state.anchorKey.value = 7
        state.rangeLoadStates.value = mapOf(
            0..4 to LoadState.Error(boom, 0),
            5..9 to LoadState.Success,
            10..14 to LoadState.Loading,
        )

        state.prefetchStateFlow.first() shouldBe LoadState.Loading
    }

    @Test
    fun streamed_messages_do_not_re_emit_unchanged_states() = runTest {
        val state = state()
        state.anchorKey.value = 2
        val loads = mutableListOf<LoadState>()
        val prefetches = mutableListOf<LoadState>()
        val jobs = listOf(
            launch { state.loadStateFlow.collect { loads += it } },
            launch { state.prefetchStateFlow.collect { prefetches += it } },
        )
        runCurrent()

        state.rangeLoadStates.value =
            mapOf(0..4 to LoadState.Loading, 5..9 to LoadState.Loading, 10..14 to LoadState.Loading)
        runCurrent()
        // A side range settling while another one still loads changes neither state.
        state.rangeLoadStates.value =
            mapOf(0..4 to LoadState.Loading, 5..9 to LoadState.Success, 10..14 to LoadState.Loading)
        runCurrent()
        state.rangeLoadStates.value =
            mapOf(0..4 to LoadState.Success, 5..9 to LoadState.Success, 10..14 to LoadState.Loading)
        runCurrent()
        state.rangeLoadStates.value =
            mapOf(0..4 to LoadState.Success, 5..9 to LoadState.Success, 10..14 to LoadState.Success)
        runCurrent()

        loads shouldBe listOf(LoadState.Loading, LoadState.Success)
        prefetches shouldBe listOf(LoadState.Success, LoadState.Loading, LoadState.Success)

        jobs.forEach { it.cancel() }
    }
}
