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
     * F2: chunks inside one loading pass are fetched strictly sequentially
     *     (maxInFlight == 1), so filling the preload window costs N round trips.
     */
    @Test
    fun f1_f2_first_paint_is_not_debounced_but_fetches_are_serial() = runTest {
        val calls = Calls()
        val p = pager(calls, latencyMs = 100)
        var firstDataAt = -1L
        val job = launch {
            p.flow.collectLatest { if (firstDataAt < 0 && it.data.values.isNotEmpty()) firstDataAt = currentTime }
        }
        testScheduler.advanceUntilIdle()

        // 100 ms network only: the initial load had nothing to debounce.
        firstDataAt shouldBe 100L
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
        checkNotNull(latest).data[500]
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

        checkNotNull(latest).data[500]
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

        // ...yet the pager fetches it all again
        calls.starts shouldBe listOf(90, 110, 80, 130, 60)
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
