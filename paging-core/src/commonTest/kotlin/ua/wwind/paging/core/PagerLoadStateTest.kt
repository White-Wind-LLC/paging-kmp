package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PagerLoadStateTest {

    private val boom = IllegalStateException("boom")

    /** A 1 000 item source whose chunks, keyed by their start, can be delayed or made to fail. */
    private inner class Source(
        val delayOf: (start: Int) -> Long = { 0 },
        val failuresLeft: MutableMap<Int, Int> = mutableMapOf(),
        val failAfterEmit: Set<Int> = emptySet(),
        val shortChunks: Map<Int, Int> = emptyMap(),
        val concurrency: Int = 4,
    ) {
        val requested = mutableListOf<Int>()

        fun pager(): Pager<Int> = Pager(
            loadSize = 20,
            preloadSize = 60,
            cacheSize = 100,
            keyDebounceMs = 300,
            concurrency = concurrency,
        ) { pos, size ->
            flow {
                requested += pos
                delay(delayOf(pos))
                val left = failuresLeft[pos] ?: 0
                if (left > 0) {
                    failuresLeft[pos] = left - 1
                    throw boom
                }
                val rows = shortChunks[pos] ?: size
                emit(DataPortion(1_000, (pos..<pos + rows).associateWith { it }.toPersistentMap()))
                if (pos in failAfterEmit) throw boom
            }
        }
    }

    /** Records state transitions; [clearTransitions] keeps the current states as the baseline. */
    private class Observed {
        var latest: PagingData<Int>? = null
        val emissions = mutableListOf<PagingData<Int>>()
        val loadStates = mutableListOf<String>()
        val prefetchStates = mutableListOf<String>()
        private var lastLoad: String? = null
        private var lastPrefetch: String? = null

        val current: PagingData<Int> get() = checkNotNull(latest)

        fun record(paging: PagingData<Int>) {
            latest = paging
            emissions += paging
            val load = paging.loadState::class.simpleName!!
            if (lastLoad != load) loadStates += load
            lastLoad = load
            val prefetch = paging.prefetchState::class.simpleName!!
            if (lastPrefetch != prefetch) prefetchStates += prefetch
            lastPrefetch = prefetch
        }

        fun clearTransitions() {
            loadStates.clear()
            prefetchStates.clear()
        }
    }

    private fun TestScope.observe(pager: Pager<Int>): Pair<Observed, Job> {
        val observed = Observed()
        val job = launch { pager.flow.collectLatest { observed.record(it) } }
        testScheduler.advanceUntilIdle()
        return observed to job
    }

    /** Reads [key] and waits out the access debounce, so the pass for it starts now. */
    private fun TestScope.access(observed: Observed, key: Int) {
        observed.current.data[key]
        testScheduler.advanceTimeBy(301)
        testScheduler.runCurrent()
    }

    @Test
    fun jump_clears_loading_when_the_anchor_chunk_lands() = runTest {
        val source = Source(delayOf = { if (it == 500) 100 else 300 })
        val (observed, job) = observe(source.pager())

        access(observed, 500)
        observed.current.loadState shouldBe LoadState.Loading

        testScheduler.advanceTimeBy(150)
        testScheduler.runCurrent()
        observed.current.data.values.containsKey(500) shouldBe true
        observed.current.loadState shouldBe LoadState.Success
        observed.current.prefetchState shouldBe LoadState.Loading

        testScheduler.advanceUntilIdle()
        observed.current.loadState shouldBe LoadState.Success
        observed.current.prefetchState shouldBe LoadState.Success
        job.cancel()
    }

    @Test
    fun scroll_inside_cached_window_keeps_load_state_success() = runTest {
        val source = Source()
        val (observed, job) = observe(source.pager())
        access(observed, 10) // fills 0..79
        testScheduler.advanceUntilIdle()
        observed.clearTransitions()

        observed.current.data[65] // cached, but near enough to the edge to move the window
        testScheduler.advanceUntilIdle()

        source.requested.contains(80) shouldBe true
        observed.loadStates shouldBe emptyList()
        observed.prefetchStates shouldBe listOf("Loading", "Success")
        job.cancel()
    }

    @Test
    fun failing_prefetch_chunk_does_not_touch_load_state() = runTest {
        val source = Source(failuresLeft = mutableMapOf(40 to Int.MAX_VALUE))
        val (observed, job) = observe(source.pager())

        // The first pass only knows one chunk; this one plans the whole window around 10.
        access(observed, 10)
        testScheduler.advanceUntilIdle()

        observed.current.loadState shouldBe LoadState.Success
        observed.current.prefetchState shouldBe LoadState.Error(boom, key = 10)
        observed.current.data.values.containsKey(20) shouldBe true
        observed.current.data.values.containsKey(60) shouldBe true
        observed.current.data.values.containsKey(40) shouldBe false
        job.cancel()
    }

    @Test
    fun failing_anchor_chunk_sets_load_state_error_with_anchor_key() = runTest {
        val source = Source(failuresLeft = mutableMapOf(500 to Int.MAX_VALUE))
        val (observed, job) = observe(source.pager())

        access(observed, 505)
        testScheduler.advanceUntilIdle()

        observed.current.loadState shouldBe LoadState.Error(boom, key = 505)
        observed.current.prefetchState shouldBe LoadState.Success
        listOf(440, 460, 480, 520, 540, 560).forEach { start ->
            observed.current.data.values.containsKey(start) shouldBe true
        }
        job.cancel()
    }

    @Test
    fun anchor_flow_failing_after_emitting_sets_error_over_visible_data() = runTest {
        val source = Source(failAfterEmit = setOf(0))
        val (observed, job) = observe(source.pager())

        observed.current.data.values.containsKey(0) shouldBe true
        observed.current.loadState.shouldBeInstanceOf<LoadState.Error>()
        job.cancel()
    }

    @Test
    fun retry_with_error_key_recovers_both_states() = runTest {
        val source = Source(failuresLeft = mutableMapOf(40 to 1))
        val (observed, job) = observe(source.pager())
        access(observed, 10)
        testScheduler.advanceUntilIdle()
        val error = observed.current.prefetchState.shouldBeInstanceOf<LoadState.Error>()

        observed.current.retry(error.key)
        testScheduler.advanceUntilIdle()

        observed.current.loadState shouldBe LoadState.Success
        observed.current.prefetchState shouldBe LoadState.Success
        source.requested.count { it == 40 } shouldBe 2
        observed.current.data.values.containsKey(40) shouldBe true
        job.cancel()
    }

    @Test
    fun failing_conflict_round_counts_as_prefetch() = runTest {
        // Rows 39 and 40 share a key, so the pass ends with a round refetching cells 20 and 40.
        val reads = mutableMapOf<Int, Int>()
        val pager = Pager<Pair<String, Int>>(
            loadSize = 20,
            preloadSize = 60,
            cacheSize = 100,
            itemKey = { it.first },
        ) { pos, size ->
            flow {
                val read = reads.getOrElse(pos) { 0 }
                reads[pos] = read + 1
                if (pos == 40 && read == 1) throw boom
                val rows = (pos..<pos + size).associateWith { i -> (if (i == 39 || i == 40) "u" else "r$i") to i }
                emit(DataPortion(1_000, rows.toPersistentMap()))
            }
        }
        var latest: PagingData<Pair<String, Int>>? = null
        val job = launch { pager.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        checkNotNull(latest).data[10]
        testScheduler.advanceUntilIdle()

        reads[40] shouldBe 2
        checkNotNull(latest).loadState shouldBe LoadState.Success
        checkNotNull(latest).prefetchState shouldBe LoadState.Error(boom, key = 10)
        job.cancel()
    }

    @Test
    fun refresh_goes_loading_then_success() = runTest {
        val source = Source(delayOf = { 50 })
        val pager = source.pager()
        val (observed, job) = observe(pager)
        observed.clearTransitions()

        pager.refresh()
        testScheduler.advanceUntilIdle()

        observed.loadStates shouldBe listOf("Loading", "Success")
        observed.emissions.none { it.data.values.isEmpty() && it.loadState == LoadState.Success } shouldBe true
        job.cancel()
    }

    @Test
    fun access_inside_running_pass_re_anchors_load_state() = runTest {
        // One chunk at a time: 20, 40 and 60 land at 500, 1 000 and 1 500 after the pass starts.
        val source = Source(delayOf = { if (it == 0) 0 else 500 }, concurrency = 1)
        val (observed, job) = observe(source.pager())
        access(observed, 10)
        observed.current.loadState shouldBe LoadState.Success

        access(observed, 60)
        observed.current.loadState shouldBe LoadState.Loading

        testScheduler.advanceTimeBy(900) // chunk 40 has landed, 60 is in flight
        testScheduler.runCurrent()
        observed.current.data.values.containsKey(40) shouldBe true
        observed.current.loadState shouldBe LoadState.Loading

        testScheduler.advanceUntilIdle()
        observed.current.data.values.containsKey(60) shouldBe true
        observed.current.loadState shouldBe LoadState.Success
        source.requested shouldBe listOf(0, 20, 40, 60) // the pass for 10 only, nothing refetched
        job.cancel()
    }

    @Test
    fun access_inside_running_pass_onto_failed_chunk_reports_error() = runTest {
        val source = Source(
            delayOf = { if (it == 20) 100 else 500 },
            failuresLeft = mutableMapOf(20 to Int.MAX_VALUE),
            concurrency = 1,
        )
        val (observed, job) = observe(source.pager())
        access(observed, 10) // chunk 20 fails at 100, before the access to 25 is debounced

        access(observed, 25)
        observed.current.loadState shouldBe LoadState.Error(boom, key = 25)

        testScheduler.advanceUntilIdle()
        observed.current.loadState shouldBe LoadState.Error(boom, key = 25)
        observed.current.prefetchState shouldBe LoadState.Success
        job.cancel()
    }

    @Test
    fun anchor_chunk_failure_moves_to_prefetch_when_the_user_moves_on() = runTest {
        val source = Source(
            delayOf = { if (it == 500) 10 else 500 },
            failuresLeft = mutableMapOf(500 to Int.MAX_VALUE),
        )
        val (observed, job) = observe(source.pager())
        access(observed, 505)
        testScheduler.advanceTimeBy(20)
        testScheduler.runCurrent()
        observed.current.loadState shouldBe LoadState.Error(boom, key = 505)

        access(observed, 545) // chunk 540 is still in flight
        observed.current.loadState shouldBe LoadState.Loading

        testScheduler.advanceUntilIdle()
        observed.current.loadState shouldBe LoadState.Success
        observed.current.prefetchState shouldBe LoadState.Error(boom, key = 545)
        job.cancel()
    }

    @Test
    fun short_anchor_chunk_ends_loading_when_it_completes() = runTest {
        // Chunk 500 returns rows 500..504 only, so 510 never reaches the cache.
        val source = Source(delayOf = { if (it == 500) 100 else 300 }, shortChunks = mapOf(500 to 5))
        val (observed, job) = observe(source.pager())

        access(observed, 510)
        observed.current.loadState shouldBe LoadState.Loading

        testScheduler.advanceTimeBy(150)
        testScheduler.runCurrent()
        observed.current.data.values.containsKey(510) shouldBe false
        observed.current.loadState shouldBe LoadState.Success
        observed.current.prefetchState shouldBe LoadState.Loading
        job.cancel()
    }

    @Test
    fun lingering_prefetch_error_key_follows_the_user() = runTest {
        val source = Source(failuresLeft = mutableMapOf(40 to Int.MAX_VALUE))
        val (observed, job) = observe(source.pager())
        access(observed, 10)
        testScheduler.advanceUntilIdle()
        observed.current.prefetchState shouldBe LoadState.Error(boom, key = 10)

        access(observed, 20) // cached
        testScheduler.advanceUntilIdle()

        (observed.current.prefetchState as LoadState.Error).key shouldBe 20
        job.cancel()
    }

    @Test
    fun lingering_prefetch_error_key_follows_a_re_anchor() = runTest {
        val source = Source(delayOf = { if (it == 40) 500 else 0 }, failuresLeft = mutableMapOf(40 to Int.MAX_VALUE))
        val (observed, job) = observe(source.pager())
        access(observed, 70) // fills 0..139 apart from the failing chunk 40
        testScheduler.advanceUntilIdle()
        observed.current.prefetchState shouldBe LoadState.Error(boom, key = 70)

        access(observed, 45) // the pass for 45 only fetches its own chunk, 40
        observed.current.loadState shouldBe LoadState.Loading
        observed.current.prefetchState shouldBe LoadState.Error(boom, key = 45)

        access(observed, 50) // same chunk, still in flight
        (observed.current.prefetchState as LoadState.Error).key shouldBe 50
        job.cancel()
    }
}
