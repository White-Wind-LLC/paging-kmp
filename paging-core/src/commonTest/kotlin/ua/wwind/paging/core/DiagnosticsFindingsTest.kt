package ua.wwind.paging.core

import io.kotest.assertions.throwables.shouldThrow
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
        concurrency: Int = 4,
    ) = Pager<Int>(loadSize, preloadSize, cacheSize, concurrency = concurrency) { pos, size ->
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
            }.distinctUntilChanged().collect {
                portionEmissions++
                emit(it)
            }
        }
    }

    private fun streamingPager(s: FakeStream, config: ua.wwind.paging.core.stream.StreamingPagerConfig) =
        ua.wwind.paging.core.stream.StreamingPager<String>(config, { s.total() }, { p, n -> s.portion(p, n) })

    // ------------------------------------------------------------ F1 / F2 / F3

    /**
     * F1 (fixed): the key debounce used to be applied to the *initial* load as well, so first paint
     *     cost 300 ms + one round trip even though nothing needed debouncing. The first key emission
     *     now bypasses the debounce, leaving only the round trip.
     */
    @Test
    fun f1_first_paint_is_not_debounced() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 100)
        var firstDataAt = -1L
        val job = launch {
            p.flow.collectLatest { if (firstDataAt < 0 && it.data.values.isNotEmpty()) firstDataAt = currentTime }
        }
        testScheduler.advanceUntilIdle()

        // 100 ms network only: the initial load had nothing to debounce.
        firstDataAt shouldBe 100L
        job.cancel()
    }

    /**
     * F2 (fixed): chunks inside one loading pass used to be fetched strictly sequentially
     * (`maxInFlight == 1`), so a jump to a far position paid one round trip per chunk of the
     * preload window - 900 ms with a 100 ms backend. Up to `concurrency` chunks are now in flight
     * at a time, and the mutex is no longer held across the fetches.
     */
    @Test
    fun f2_jump_fills_the_preload_window_concurrently() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 100, concurrency = 4)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        calls.starts.clear()

        val t0 = currentTime
        checkNotNull(latest).data[500]
        testScheduler.advanceUntilIdle()

        calls.starts.size shouldBe 6
        calls.maxInFlight shouldBe 4
        // 300 ms debounce + 6 chunks over 2 round trips, instead of 6 round trips
        (currentTime - t0) shouldBe 500L
        job.cancel()
    }

    /**
     * F2b (fixed): the direction the position was moving in was computed and then thrown away by a
     * distance sort spanning both sides of the key, so the window filled symmetrically and half the
     * requests went to the side being scrolled away from. Each side is now ordered on its own and
     * the leading one is fetched first.
     */
    @Test
    fun f2b_the_window_fills_towards_the_scroll_direction() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 1, concurrency = 1)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        calls.starts.clear()

        checkNotNull(latest).data[500] // moving up from 0, so 500+ is what comes into view
        testScheduler.advanceUntilIdle()

        calls.starts shouldBe listOf(500, 520, 540, 480, 460, 440)
        job.cancel()
    }

    /**
     * F3 (fixed): chunk boundaries used to be centred on the accessed key, so overlapping ranges
     * (`490..509` and `480..499`) fetched the same positions twice in a single pass. Chunks are now
     * snapped to a `loadSize` grid: the preload window is tiled exactly once and the same absolute
     * positions are always requested under the same `(offset, limit)` pair.
     */
    @Test
    fun f3_chunks_are_grid_aligned() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 1)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        calls.starts.clear()

        checkNotNull(latest).data[500]
        testScheduler.advanceUntilIdle()

        // the chunk holding the key comes first, the rest tiles the window outwards
        calls.starts.sorted() shouldBe listOf(440, 460, 480, 500, 520, 540)
        calls.starts.all { it % 20 == 0 } shouldBe true
        // no range is requested twice and none of them overlap
        calls.starts.distinct() shouldBe calls.starts
        job.cancel()
    }

    // ------------------------------------------------------------------- F4

    /**
     * F4 (fixed): the pager used to recognise only ONE contiguous run of cached data
     * (`findContinuousRange` started from the arithmetic mean of the loaded keys). With a fragmented
     * cache the mean fell into a gap, the run came back as `null`, and a region already fully in
     * memory was refetched. Every missing sub-range of the window is now computed separately, so a
     * fully cached window costs no requests at all.
     */
    @Test
    fun f4_fragmented_cache_is_not_refetched() = runTest {
        val calls = Calls()
        val p = pager(calls, preloadSize = 40, cacheSize = 1_000, latencyMs = 1)
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        checkNotNull(latest).data[100]
        testScheduler.advanceUntilIdle()
        latest.data[600]
        testScheduler.advanceUntilIdle()

        // everything around 100 is cached and nothing was evicted (cacheSize = 1000)
        val cachedAround100 = (60..140).count { it in latest.data.values }
        cachedAround100 shouldBe 80
        calls.starts.clear()

        latest.data[100]
        testScheduler.advanceUntilIdle()

        // ...and nothing is fetched again: the second cluster no longer hides the first one
        calls.starts shouldBe emptyList()
        job.cancel()
    }

    // ------------------------------------------------------------------- F5

    /**
     * F5 (fixed): `retry(key)` used to be routed through the StateFlow that was already holding
     * `key`, so the documented recovery path (`retry(loadState.key)`, used by the README and by the
     * sample's ErrorOverlay) was a no-op and only a *different* key recovered. Retries now travel on
     * their own channel and are always honoured.
     */
    @Test
    fun f5_retry_with_the_failed_key_reloads() = runTest {
        var failNext = true
        val attempts = mutableListOf<Int>()
        val p = Pager<Int>(20, 20, 100) { pos, size ->
            flow {
                attempts += pos
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("boom")
                }
                emit(DataPortion(1_000, (pos..pos + size - 1).associateWith { it }.toPersistentMap()))
            }
        }
        var latest: PagingData<Int>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        val failedKey = (checkNotNull(latest).loadState as LoadState.Error).key
        val before = attempts.size

        latest.retry(failedKey) // the documented call
        testScheduler.advanceUntilIdle()

        attempts.size shouldBe before + 1
        latest.loadState shouldBe LoadState.Success
        job.cancel()
    }

    // ------------------------------------------------------------------- F6

    /**
     * F6 (fixed): `refresh()` used to clear the cache without re-triggering a load, and the
     * conflating key StateFlow swallowed the subsequent access to the same key, so the list stayed
     * empty while reporting LoadState.Success. It now reloads the current window itself.
     */
    @Test
    fun f6_refresh_reloads_the_current_window() = runTest {
        var portions = 0
        val p = Pager<String>(20, 20, 100) { pos, size ->
            flow {
                portions++
                delay(50)
                emit(DataPortion(1_000, (pos..pos + size - 1).associateWith { "v$it" }.toPersistentMap()))
            }
        }
        var latest: PagingData<String>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        checkNotNull(latest).data.values.size shouldBe 20
        val before = portions

        p.refresh()
        testScheduler.advanceUntilIdle()

        portions shouldBe before + 1 // the window was fetched again
        latest.data.values.size shouldBe 20
        latest.loadState shouldBe LoadState.Success
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

        checkNotNull(latest).data[70] // small scroll, data already cached
        testScheduler.advanceUntilIdle()

        transitions shouldBe listOf("Loading", "Success")
        job.cancel()
    }

    /**
     * F7b (fixed): `onTotalChanged` used to clamp the read key to the new total rather than to the
     * last valid index, so the key landed exactly on the boundary and `alignedChunkContaining`
     * returned an EMPTY chunk. `tryAdjustStreamsForKey` marked it Loading, `openStream`
     * early-returned for it, and the marker was never cleared - the pager reported Loading forever.
     *
     * The key is now clamped to `newTotal - 1`, `planStreamWindow` clamps out-of-bounds keys itself,
     * and empty ranges are never marked Loading. The pager settles back to Success.
     */
    @Test
    fun f7b_total_shrink_settles_back_to_success() = runTest {
        val s = FakeStream(100)
        val p = streamingPager(s, ua.wwind.paging.core.stream.StreamingPagerConfig(20, 20, 200))
        var latest: PagingData<String>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()
        checkNotNull(latest).data[95]
        testScheduler.advanceUntilIdle()
        latest.loadState shouldBe LoadState.Success

        s.items.value = s.items.value.take(40) // total shrinks 100 -> 40
        testScheduler.advanceUntilIdle()

        latest.data.size shouldBe 40
        // every item is present, and the pager reports it
        latest.data.values.size shouldBe 40
        latest.loadState shouldBe LoadState.Success
        job.cancel()
    }

    // ------------------------------------------------------------------- F8

    /**
     * F8 (fixed): nothing used to validate `cacheSize` against `preloadSize`. With
     * `cacheSize < preloadSize` the pager kept streams open for ranges whose payload was dropped by
     * `onPortion`'s cacheRange filter the moment it arrived - 340 items over the wire for 81
     * retained on a jump to position 500, ~76% discarded, with 11 streams left open pushing data
     * that went straight into the bin.
     *
     * Such a configuration is now rejected at construction time.
     */
    @Test
    fun f8_cache_smaller_than_preload_is_rejected() {
        shouldThrow<IllegalArgumentException> {
            ua.wwind.paging.core.stream.StreamingPagerConfig(20, 100, 40)
        }
    }

    /**
     * F8 (cont.): with the invariant satisfied, everything the viewport streams is also retained -
     * the payload of the streams opened for the jump survives `onPortion` in full.
     *
     * Only the portions streamed *after* the jump are counted: the initial load around position 0
     * is legitimately evicted once the viewport moves 500 items away from it.
     */
    @Test
    fun f8b_a_valid_cache_retains_everything_the_viewport_streams() = runTest {
        val s = FakeStream(1_000)
        val p = streamingPager(s, ua.wwind.paging.core.stream.StreamingPagerConfig(20, 100, 120))
        var latest: PagingData<String>? = null
        val job = launch { p.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        val openedBeforeJump = s.portionOpens.size
        checkNotNull(latest).data[500]
        testScheduler.advanceUntilIdle()

        val streamedForViewport = s.portionOpens.drop(openedBeforeJump).sumOf { it.second }
        val retained = latest.data.values.size
        streamedForViewport shouldBe 220
        retained shouldBe streamedForViewport
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
                    "stationary=$stationary speedup=${old / merged} / ${old / stationary}",
            )
        }
    }
}
