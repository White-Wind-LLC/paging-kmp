package ua.wwind.paging.core.stream

import co.touchlab.kermit.Logger
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.LoadState
import kotlin.test.Test

/**
 * Tests for the aggregated load state of `StreamingPager` (#12).
 *
 * The per-range map changes far more often than the single state derived from it: every message of
 * every portion stream touches it, and only the first one of a range moves the aggregate. Each
 * repeat used to reach the UI as another `PagingData`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPagerStateTest {

    private fun state() = StreamingPagerState<Int>(
        config = StreamingPagerConfig(loadSize = 5, preloadSize = 5, cacheSize = 100, keyDebounceMs = 0),
        readPortion = { _, _ -> emptyFlow() },
        logger = Logger,
    )

    @Test
    fun a_range_settling_while_another_is_loading_does_not_re_emit_the_load_state() = runTest {
        val state = state()
        val seen = mutableListOf<LoadState>()
        val job = launch { state.loadStateFlow.collect { seen += it } }
        runCurrent()

        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Loading, 5..9 to LoadState.Loading)
        runCurrent()
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Success, 5..9 to LoadState.Loading)
        runCurrent()

        // The aggregate never left Loading, so the UI has nothing new to render.
        seen shouldBe listOf(LoadState.Loading)

        job.cancel()
    }

    @Test
    fun a_real_transition_is_still_reported() = runTest {
        val state = state()
        val seen = mutableListOf<LoadState>()
        val job = launch { state.loadStateFlow.collect { seen += it } }
        runCurrent()

        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Loading)
        runCurrent()
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Success)
        runCurrent()
        val boom = IllegalStateException("boom")
        state.rangeLoadStates.value = mapOf(0..4 to LoadState.Error(boom, 0))
        runCurrent()

        seen shouldBe listOf(LoadState.Loading, LoadState.Success, LoadState.Error(boom, 0))

        job.cancel()
    }
}
