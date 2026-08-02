package ua.wwind.paging.core

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.stream.StreamingPager
import ua.wwind.paging.core.stream.StreamingPagerConfig
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class StreamingPagerTest {

    private class TestSource<T> {
        val totalFlow = MutableStateFlow(0)
        private val portionFlows: MutableMap<Pair<Int, Int>, MutableSharedFlow<Map<Int, T>>> = LinkedHashMap()

        fun readTotal(): Flow<Int> = totalFlow

        fun readPortion(start: Int, size: Int): Flow<Map<Int, T>> =
            portionFlows.getOrPut(start to size) { MutableSharedFlow(replay = 1) }

        suspend fun emitPortion(start: Int, size: Int, values: Map<Int, T>) {
            portionFlows[start to size]?.emit(values)
        }

        fun hasPortion(start: Int, size: Int): Boolean = (start to size) in portionFlows

        /** Number of live collectors of a portion flow - 0 once the pager has closed its stream. */
        fun subscribers(start: Int, size: Int): Int = portionFlows[start to size]?.subscriptionCount?.value ?: 0
    }

    private fun buildPager(
        scope: TestScope,
        config: StreamingPagerConfig = StreamingPagerConfig(
            loadSize = 5,
            preloadSize = 5,
            cacheSize = 100,
            closeThreshold = 5,
            keyDebounceMs = 0,
        ),
        source: TestSource<Int>,
    ): Pair<StreamingPager<Int>, suspend (Int) -> Unit> {
        val pager: StreamingPager<Int> = StreamingPager(
            config = config,
            readTotal = { source.readTotal() },
            readPortion = { s, sz -> source.readPortion(s, sz) },
        )

        // helper to advance virtual time sufficiently to pass debounce and complete loads
        val advanceFully: suspend (Int) -> Unit = { extraMs ->
            if (config.keyDebounceMs > 0) scope.testScheduler.advanceTimeBy(config.keyDebounceMs)
            if (extraMs > 0) scope.testScheduler.advanceTimeBy(extraMs.toLong())
            scope.testScheduler.advanceUntilIdle()
        }

        return pager to advanceFully
    }

    @Test
    fun total_updates_size() = runTest {
        val src = TestSource<Int>()
        val (pager, advanceFully) = buildPager(this, source = src)

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }

        // Initially zero
        advanceFully(0)
        (latest?.data?.size ?: 0) shouldBe 0

        // Update total
        src.totalFlow.value = 50
        advanceFully(100)
        latest?.data?.size shouldBe 50

        job.cancel()
    }

    @Test
    fun loads_portion_on_access_and_merges_values() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 50
        val (pager, advanceFully) = buildPager(
            this,
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 300,
            ),
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }

        // Trigger access for key 0 -> should request portion [0..4]
        val initial = latest ?: pager.flow.first()
        initial.data[0] // triggers onGet
        advanceFully(350) // debounce

        // Ensure portion flow created
        // Emit values for [0..4]
        src.emitPortion(0, 5, (0..4).associateWith { it })
        advanceFully(10)

        val after = latest.shouldNotBeNull()
        val entry = after.data[0].shouldBeInstanceOf<EntryState.Success<Int>>()
        entry.value shouldBe 0

        job.cancel()
    }

    @Test
    fun total_shrink_prunes_out_of_bounds() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 20
        val (pager, advanceFully) = buildPager(
            this,
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 0,
            ),
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(50)

        // Load [0..9]
        checkNotNull(latest).data[0]
        advanceFully(50)
        src.emitPortion(0, 5, (0..4).associateWith { it })
        advanceFully(10)
        latest.data[8]
        advanceFully(50)
        src.emitPortion(5, 5, (5..9).associateWith { it })
        advanceFully(10)

        // Now shrink total to 7 -> keys > 6 must be pruned
        src.totalFlow.value = 7
        advanceFully(10)
        latest.data.size shouldBe 7
        // lastKey should be <= 6
        (latest.data.lastKey() <= 6) shouldBe true

        job.cancel()
    }

    /**
     * Regression for #5: the clamp applied when the total shrinks used to land the read key exactly
     * on the new boundary, which produced an empty centre chunk that was marked `Loading` and never
     * opened - so the pager reported `Loading` forever even with every item cached.
     */
    @Test
    fun total_shrink_past_the_viewport_settles_the_load_state() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 20
        val (pager, advanceFully) = buildPager(this, source = src)

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(10)

        src.emitPortion(0, 5, (0..4).associateWith { it })
        src.emitPortion(5, 5, (5..9).associateWith { it })
        advanceFully(10)

        // Move the viewport to the tail of the list.
        checkNotNull(latest).data[18]
        advanceFully(10)
        src.emitPortion(10, 5, (10..14).associateWith { it })
        src.emitPortion(15, 5, (15..19).associateWith { it })
        advanceFully(10)
        latest.loadState shouldBe LoadState.Success

        // Total shrinks well below the last read key (18 -> clamped to index 9).
        src.totalFlow.value = 10
        advanceFully(10)

        latest.data.size shouldBe 10
        latest.loadState shouldBe LoadState.Success

        job.cancel()
    }

    /**
     * Regression for #5: the close filter used `> newTotal`, but valid indices are `0..<newTotal`,
     * so a stream covering exactly index `newTotal` survived the shrink.
     */
    @Test
    fun total_shrink_closes_the_stream_covering_the_new_boundary_index() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 20
        val (pager, advanceFully) = buildPager(this, source = src)

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(10)

        // Read at 7 so that [5..9] and [10..14] are streaming alongside [0..4].
        checkNotNull(latest).data[7]
        advanceFully(10)
        src.emitPortion(0, 5, (0..4).associateWith { it })
        src.emitPortion(5, 5, (5..9).associateWith { it })
        src.emitPortion(10, 5, (10..14).associateWith { it })
        advanceFully(10)
        src.subscribers(5, 5) shouldBe 1

        // Shrink to 9: valid indices are 0..8, so [5..9] is out of bounds by exactly one index.
        src.totalFlow.value = 9
        advanceFully(10)

        latest.data.size shouldBe 9
        src.subscribers(5, 5) shouldBe 0
        src.subscribers(10, 5) shouldBe 0
        // [0..4] is fully in bounds and stays put, so nothing is left loading.
        src.subscribers(0, 5) shouldBe 1
        latest.loadState shouldBe LoadState.Success

        job.cancel()
    }

    @Test
    fun portion_after_the_viewport_moved_prunes_values_outside_the_new_cache_range() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 200
        val (pager, advanceFully) = buildPager(
            this,
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 10,
                closeThreshold = 500,
                keyDebounceMs = 0,
            ),
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(50)

        // Fill the cache around key 0
        checkNotNull(latest).data[0]
        advanceFully(50)
        src.emitPortion(0, 5, (0..4).associateWith { it })
        advanceFully(10)
        latest.data.values.keys.contains(0) shouldBe true

        // Move the viewport far away: cacheRange becomes 90..110
        latest.data[100]
        advanceFully(50)
        src.emitPortion(100, 5, (100..104).associateWith { it })
        advanceFully(10)

        // The incoming portion is cached, the values left behind by the old viewport are gone
        latest.data.values.keys.contains(100) shouldBe true
        latest.data.values.keys.none { it < 90 } shouldBe true

        job.cancel()
    }

    @Test
    fun loadState_loading_then_success_when_new_range_opens() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 50
        val (pager, advanceFully) = buildPager(
            this,
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 300,
            ),
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(350)

        // Open first range [0..4]
        checkNotNull(latest).data[2]
        advanceFully(350)
        src.emitPortion(0, 5, (0..4).associateWith { it })
        src.emitPortion(5, 5, (5..9).associateWith { it })
        advanceFully(100)
        latest.loadState shouldBe LoadState.Success

        // Access far key to open another range near 20
        latest.data[20]
        advanceFully(350)
        // New ranges opened; global state will update after emissions

        // Emit surrounded ranges (backward, center, forward)
        src.emitPortion(15, 5, (15..19).associateWith { it })
        src.emitPortion(20, 5, (20..24).associateWith { it })
        src.emitPortion(25, 5, (25..29).associateWith { it })
        advanceFully(10)
        latest.loadState shouldBe LoadState.Success

        job.cancel()
    }

    @Test
    fun readTotal_error_then_retry_restarts_collection() = runTest {
        val totalFlow = MutableSharedFlow<Int>(replay = 1)
        var totalCalls = 0
        val portionFlows: MutableMap<Pair<Int, Int>, MutableSharedFlow<Map<Int, Int>>> = LinkedHashMap()

        val config = StreamingPagerConfig(
            loadSize = 5,
            preloadSize = 5,
            cacheSize = 100,
            closeThreshold = 5,
            keyDebounceMs = 1,
        )

        val pager: StreamingPager<Int> = StreamingPager(
            config = config,
            readTotal = {
                flow {
                    totalCalls++
                    if (totalCalls == 1) {
                        throw IllegalStateException("boom")
                    }
                    emitAll(totalFlow)
                }
            },
            readPortion = { start, size ->
                portionFlows.getOrPut(start to size) { MutableSharedFlow(replay = 1) }
            },
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }

        testScheduler.runCurrent()
        latest?.loadState.shouldBeInstanceOf<LoadState.Error>()
        totalCalls shouldBe 1

        latest.retry(0)
        testScheduler.runCurrent()
        totalCalls shouldBe 2
        latest.loadState shouldBe LoadState.Loading

        totalFlow.emit(10)
        testScheduler.runCurrent()
        latest.data.size shouldBe 10

        job.cancel()
    }

    @Test
    fun retry_reopens_a_failed_stream_at_the_position_already_being_read() = runTest {
        val opens = mutableListOf<Int>()
        var failing = true
        val total = MutableStateFlow(50)

        val pager: StreamingPager<Int> = StreamingPager(
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 300,
            ),
            readTotal = { total },
            readPortion = { start, size ->
                flow {
                    opens += start
                    if (start == 0 && failing) error("boom at $start")
                    emitAll(MutableStateFlow((start..<(start + size).coerceAtMost(50)).associateWith { it }))
                }
            },
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        testScheduler.advanceUntilIdle()

        checkNotNull(latest).data[0]
        testScheduler.advanceUntilIdle()
        // The chunk holding 0 failed and was dropped, so nothing is streaming it any more
        latest.data.values.containsKey(0) shouldBe false
        val attemptsBefore = opens.count { it == 0 }

        failing = false
        // The consumer has not moved, so the retry names the position it is already reading
        checkNotNull(latest).retry(0)
        testScheduler.advanceUntilIdle()

        opens.count { it == 0 } shouldBe attemptsBefore + 1
        latest.data.values.containsKey(0) shouldBe true

        job.cancel()
    }

    @Test
    fun config_rejects_a_cache_narrower_than_the_preload_window() {
        val error = shouldThrow<IllegalArgumentException> {
            StreamingPagerConfig(loadSize = 20, preloadSize = 100, cacheSize = 40)
        }

        val message = error.message.orEmpty()
        message shouldContain "cacheSize (40)"
        message shouldContain "preloadSize (100)"
    }

    @Test
    fun config_accepts_a_cache_exactly_as_wide_as_the_preload_window() {
        shouldNotThrowAny {
            StreamingPagerConfig(loadSize = 20, preloadSize = 100, cacheSize = 100)
        }
    }

    @Test
    fun config_rejects_a_copy_that_breaks_the_cache_invariant() {
        val valid = StreamingPagerConfig(loadSize = 20, preloadSize = 100, cacheSize = 120)

        shouldThrow<IllegalArgumentException> {
            valid.copy(cacheSize = 40)
        }
    }
}
