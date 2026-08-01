package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Characterisation tests documenting the CURRENT behaviour of the library.
 *
 * These are not aspirational tests: every assertion below encodes what the
 * implementation does today, including the defects. They exist so that the
 * diagnostics report is reproducible and so that a future fix has a red test
 * to turn green (flip the assertion when fixing).
 *
 * Findings are numbered F1..F8 and referenced from the report.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class DiagnosticsFindingsTest {

    // ---------------------------------------------------------------- helpers

    private class Calls {
        val starts = mutableListOf<Int>()
        var inFlight = 0
        var maxInFlight = 0
    }

    private fun pager(
        calls: Calls,
        totalSize: Int = 1_000,
        loadSize: Int = 20,
        preloadSize: Int = 60,
        cacheSize: Int = 100,
        latencyMs: Long = 100,
    ) = Pager<Int>(loadSize, preloadSize, cacheSize) { pos, size ->
        flow {
            calls.starts += pos
            calls.inFlight++
            calls.maxInFlight = maxOf(calls.maxInFlight, calls.inFlight)
            delay(latencyMs)
            calls.inFlight--
            val last = (pos + size - 1).coerceAtMost(totalSize - 1)
            emit(DataPortion(totalSize, (pos..last).associateWith { it }.toPersistentMap()))
        }
    }

    private class FakeStream(total: Int) {
        val items = MutableStateFlow(List(total) { "v-$it" })
        val portionOpens = mutableListOf<Pair<Int, Int>>()
        var portionEmissions = 0

        fun total(): Flow<Int> = items.map { it.size }.distinctUntilChanged()

        fun portion(start: Int, size: Int): Flow<Map<Int, String>> = flow {
            portionOpens += start to size
            items.map { list ->
                val end = (start + size).coerceAtMost(list.size)
                if (start < end) (start until end).associateWith { list[it] } else emptyMap()
            }.distinctUntilChanged().collect { portionEmissions++; emit(it) }
        }
    }

    private fun streamingPager(s: FakeStream, config: ua.wwind.paging.core.stream.StreamingPagerConfig) =
        ua.wwind.paging.core.stream.StreamingPager<String>(config, { s.total() }, { p, n -> s.portion(p, n) })

    // ------------------------------------------------------------ F1 / F2 / F3

    /**
     * F1: the 300 ms debounce is applied to the *initial* load as well, so first
     *     paint costs 300 ms + one round trip even though nothing needs debouncing.
     * F2: chunks inside one loading pass are fetched strictly sequentially
     *     (maxInFlight == 1), so filling the preload window costs N round trips.
     */
    @Test
    fun f1_f2_first_paint_is_debounced_and_fetches_are_serial() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 100)
        var firstDataAt = -1L
        val job = launch {
            p.flow.collectLatest { if (firstDataAt < 0 && it.data.values.isNotEmpty()) firstDataAt = currentTime }
        }
        testScheduler.advanceUntilIdle()

        // 300 ms debounce + 100 ms network, for a load that had nothing to debounce.
        firstDataAt shouldBe 400L
        calls.maxInFlight shouldBe 1
        job.cancel()
    }

    /**
     * F2 (cont.): a jump to a far position issues every chunk of the preload
     * window one after another. With a 100 ms backend that is 700 ms of serial
     * network time to fill a +/-60 item window.
     */
    @Test
    fun f2_jump_serialises_the_whole_preload_window() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 100)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        calls.starts.clear()

        val t0 = currentTime
        latest!!.data[500]
        testScheduler.advanceUntilIdle()

        calls.starts.size shouldBe 7
        calls.maxInFlight shouldBe 1
        // 300 ms debounce + 7 serial round trips
        (currentTime - t0) shouldBe 1_000L
        job.cancel()
    }

    /**
     * F3: chunk boundaries are not aligned to a grid, so the same absolute
     * positions are requested under different (overlapping) ranges over time.
     */
    @Test
    fun f3_chunks_are_not_grid_aligned() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 1)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        calls.starts.clear()

        latest!!.data[500]
        testScheduler.advanceUntilIdle()

        // starts are centred on the key rather than snapped to a loadSize grid
        calls.starts shouldBe listOf(490, 510, 480, 530, 460, 550, 440)
        calls.starts.any { it % 20 != 0 } shouldBe true
        // 490..509 and 480..499 overlap: position 490..499 is fetched twice
        calls.starts.contains(490) shouldBe true
        calls.starts.contains(480) shouldBe true
        job.cancel()
    }

    // ------------------------------------------------------------------- F4

    /**
     * F4: `findContinuousRange` recognises only ONE contiguous run (it starts
     * from the arithmetic mean of the loaded keys). With a fragmented cache the
     * mean falls into a gap, the function returns null, and the pager refetches
     * a region that is already fully in memory.
     */
    @Test
    fun f4_fragmented_cache_refetches_data_already_in_memory() = runTest {
        val calls = Calls()
        val p = pager(calls, preloadSize = 40, cacheSize = 1_000, latencyMs = 1)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        latest!!.data[100]; testScheduler.advanceUntilIdle()
        latest!!.data[600]; testScheduler.advanceUntilIdle()

        // everything around 100 is cached and nothing was evicted (cacheSize = 1000)
        val cachedAround100 = (60..140).count { it in latest!!.data.values }
        cachedAround100 shouldBe 80
        calls.starts.clear()

        latest!!.data[100]
        testScheduler.advanceUntilIdle()

        // ...yet the pager fetches it all again
        calls.starts shouldBe listOf(90, 110, 80, 130, 60)
        job.cancel()
    }

    // ------------------------------------------------------------------- F5

    /**
     * F5: `retry(key)` is routed through a StateFlow that is already holding
     * `key`, so the documented recovery path (`retry(loadState.key)`, used by
     * the README and by the sample's ErrorOverlay) is a no-op. Only retrying
     * with a *different* key actually recovers.
     */
    @Test
    fun f5_retry_with_the_failed_key_does_nothing() = runTest {
        var failNext = true
        val attempts = mutableListOf<Int>()
        val p = Pager<Int>(20, 20, 100) { pos, size ->
            flow {
                attempts += pos
                if (failNext) { failNext = false; throw IllegalStateException("boom") }
                emit(DataPortion(1_000, (pos..pos + size - 1).associateWith { it }.toPersistentMap()))
            }
        }
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        val failedKey = (latest!!.loadState as LoadState.Error).key
        val before = attempts.size

        latest!!.retry(failedKey)              // the documented call
        testScheduler.advanceUntilIdle()

        attempts.size shouldBe before          // nothing was retried
        (latest!!.loadState is LoadState.Error) shouldBe true

        latest!!.retry(failedKey + 1)          // only a *different* key works
        testScheduler.advanceUntilIdle()
        attempts.size shouldBe before + 1
        latest!!.loadState shouldBe LoadState.Success
        job.cancel()
    }

    // ------------------------------------------------------------------- F6

    /**
     * F6: `refresh()` clears the cache but does not re-trigger a load, and the
     * conflating key StateFlow swallows the subsequent access to the same key.
     * The list stays empty while reporting LoadState.Success.
     */
    @Test
    fun f6_refresh_empties_the_list_without_reloading() = runTest {
        val p = Pager<String>(20, 20, 100) { pos, size ->
            flow {
                delay(50)
                emit(DataPortion(1_000, (pos..pos + size - 1).associateWith { "v$it" }.toPersistentMap()))
            }
        }
        var latest: PagingData<String>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        latest!!.data.values.size shouldBe 20

        p.refresh()
        testScheduler.advanceUntilIdle()
        latest!!.data.values.size shouldBe 0
        latest!!.loadState shouldBe LoadState.Success   // "success", but empty

        latest!!.data[0]                                // re-access same key
        testScheduler.advanceUntilIdle()
        latest!!.data.values.size shouldBe 0            // still empty
        job.cancel()
    }

    // ------------------------------------------------------------------- F7

    /**
     * F7: StreamingPager's global LoadState flips to Loading whenever the
     * window shifts, even when every visible item is already cached. UI code
     * following the README (`LoadState.Loading -> showLoader()`) flashes a
     * spinner on ordinary scrolling.
     */
    @Test
    fun f7_streaming_loadstate_flaps_on_scroll_over_cached_data() = runTest {
        val s = FakeStream(1_000)
        val p = streamingPager(s, ua.wwind.paging.core.stream.StreamingPagerConfig(20, 60, 100))
        val transitions = mutableListOf<String>()
        var latest: PagingData<String>? = null
        val job = launch {
            p.flow.collectLatest {
                latest = it
                val n = it.loadState::class.simpleName!!
                if (transitions.lastOrNull() != n) transitions += n
            }
        }
        testScheduler.advanceUntilIdle()
        transitions.clear()

        latest!!.data[70]                       // small scroll, data already cached
        testScheduler.advanceUntilIdle()

        transitions shouldBe listOf("Loading", "Success")
        job.cancel()
    }

    /**
     * F7b: when the total shrinks so that the clamped key lands exactly on the
     * new boundary, `tryAdjustStreamsForKey` marks an EMPTY chunk as Loading and
     * `openStream` early-returns for it, so the marker is never cleared and the
     * pager reports Loading forever.
     */
    @Test
    fun f7b_total_shrink_leaves_loadstate_stuck_on_loading() = runTest {
        val s = FakeStream(100)
        val p = streamingPager(s, ua.wwind.paging.core.stream.StreamingPagerConfig(20, 20, 200))
        var latest: PagingData<String>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        latest!!.data[95]
        testScheduler.advanceUntilIdle()
        latest!!.loadState shouldBe LoadState.Success

        s.items.value = s.items.value.take(40)     // total shrinks 100 -> 40
        testScheduler.advanceUntilIdle()

        latest!!.data.size shouldBe 40
        // every item is present, yet the pager is permanently "loading"
        latest!!.data.values.size shouldBe 40
        latest!!.loadState shouldBe LoadState.Loading
        job.cancel()
    }

    // ------------------------------------------------------------------- F8

    /**
     * F8: nothing validates cacheSize against preloadSize. With cacheSize <
     * preloadSize the pager keeps streams open for ranges whose payload is
     * dropped by `onPortion`'s cacheRange filter the moment it arrives.
     */
    @Test
    fun f8_cache_smaller_than_preload_streams_data_that_is_discarded() = runTest {
        val s = FakeStream(1_000)
        val p = streamingPager(s, ua.wwind.paging.core.stream.StreamingPagerConfig(20, 100, 40))
        var latest: PagingData<String>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        latest!!.data[500]
        testScheduler.advanceUntilIdle()

        val fetched = s.portionOpens.sumOf { it.second }
        val retained = latest!!.data.values.size
        fetched shouldBe 340
        retained shouldBe 81
        // ~76% of everything pulled over the wire is thrown away on arrival
        (retained.toDouble() / fetched < 0.25) shouldBe true
        job.cancel()
    }

    // ------------------------------------------------------------------- F9

    /**
     * F9 (fixed): the merge in `StreamingPagerState.onPortion` / `Pager.loadPortion` used to be
     * `(current + incoming).filterKeys{}.toPersistentMap()`, which rebuilt the whole cache on every
     * emission - three full copies, cost O(cacheSize) regardless of how small the incoming delta is.
     *
     * It now goes through [mergeIntoCache], which applies the delta in a single builder pass and keeps
     * structural sharing. This benchmark measures the shipped code against the old expression; the
     * `pruneExisting = false` column is what a stationary viewport actually pays.
     */
    @Test
    fun f9_merge_cost_is_proportional_to_cache_not_to_the_delta() {
        fun legacy(c: PersistentMap<Int, String>, inc: Map<Int, String>, r: IntRange) =
            (c + inc).filterKeys { it in r }.toPersistentMap()

        fun bench(iterations: Int, block: (Int) -> Any?): Double {
            repeat(iterations / 2) { block(it) }
            val mark = TimeSource.Monotonic.markNow()
            repeat(iterations) { block(it) }
            return mark.elapsedNow().inWholeMicroseconds.toDouble() / iterations
        }

        println("F9 merge cost (us/op) — one incoming item, varying cache size:")
        for (cacheSize in listOf(100, 200, 1_000)) {
            val cache = (0 until cacheSize * 2).associateWith { "v$it" }.toPersistentMap()
            val range = 0..(cacheSize * 2)
            val delta = mapOf(5 to "updated")
            val old = bench(3_000) { legacy(cache, delta, range) }
            val merged = bench(3_000) { cache.mergeIntoCache(delta, range) }
            val stationary = bench(3_000) { cache.mergeIntoCache(delta, range, pruneExisting = false) }
            println(
                "  cacheSize=$cacheSize entries=${cache.size}: legacy=$old merge=$merged " +
                    "stationary=$stationary speedup=${old / merged} / ${old / stationary}"
            )
        }
    }
}
