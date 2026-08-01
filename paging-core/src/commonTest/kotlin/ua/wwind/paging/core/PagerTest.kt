package ua.wwind.paging.core

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PagerTest {

    private fun buildPager(
        scope: TestScope,
        totalSize: Int = 1_000,
        loadSize: Int = 20,
        preloadSize: Int = 60,
        cacheSize: Int = 100,
        failingChunkStartOnce: Int? = null,
    ): Pair<Pager<Int>, suspend (Int) -> Unit> {
        // state to simulate one-time failure for a particular chunk start
        var hasFailed = false

        val pager: Pager<Int> = Pager(
            loadSize = loadSize,
            preloadSize = preloadSize,
            cacheSize = cacheSize,
            readData = { pos, size ->
                flow {
                    if (failingChunkStartOnce != null && pos == failingChunkStartOnce && !hasFailed) {
                        hasFailed = true
                        throw IllegalStateException("Simulated failure for chunk starting at $pos")
                    }

                    val last = (pos + size - 1).coerceAtMost(totalSize)
                    val values: Map<Int, Int> = (pos..last).associateWith { it }
                    emit(DataPortion(totalSize = totalSize, values = values.toPersistentMap()))
                }
            },
        )

        // helper to advance virtual time sufficiently to pass debounce and complete loads
        val advanceFully: suspend (Int) -> Unit = { extraMs ->
            // debounce in Pager is 300ms
            scope.testScheduler.advanceTimeBy(300)
            if (extraMs > 0) scope.testScheduler.advanceTimeBy(extraMs.toLong())
            scope.testScheduler.advanceUntilIdle()
        }

        return pager to advanceFully
    }

    @Test
    fun initialLoad_and_access_returns_success_after_debounce() = runTest {
        val (pager, advanceFully) = buildPager(this)

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }

        // Trigger load by accessing a position
        val target = 50
        // Until first emission occurs, latest can be null; advance to ensure collection starts
        this.testScheduler.advanceUntilIdle()
        // Access triggers debounced loading
        latest!!.data[target]

        advanceFully(0)

        val after = latest!!
        after.loadState.shouldBeInstanceOf<LoadState.Success>()
        // The requested key should now be present
        val entry = after.data[target].shouldBeInstanceOf<EntryState.Success<Int>>()
        entry.value shouldBe target

        // Some data around the target should be present within preload window
        (after.data.firstKey() >= 0) shouldBe true
        (after.data.lastKey() >= target) shouldBe true

        job.cancel()
    }

    @Test
    fun moving_far_evicts_outside_cache_range() = runTest {
        val cacheSize = 60
        val preloadSize = 60
        val (pager, advanceFully) = buildPager(this, cacheSize = cacheSize, preloadSize = preloadSize)

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        // First move to 50
        latest!!.data[50]
        advanceFully(0)
        val afterFirst = latest!!
        afterFirst.loadState.shouldBeInstanceOf<LoadState.Success>()

        // Then jump far to 400
        latest.data[400]
        advanceFully(0)
        val afterSecond = latest!!
        afterSecond.loadState.shouldBeInstanceOf<LoadState.Success>()

        // Validate data window roughly within preload range around 400
        val firstKey = afterSecond.data.firstKey()
        val lastKey = afterSecond.data.lastKey()
        (firstKey >= 400 - preloadSize) shouldBe true
        (lastKey < 400 + preloadSize) shouldBe true

        job.cancel()
    }

    @Test
    fun error_then_retry_succeeds_and_preserves_data() = runTest {
        // With loadSize 20 and key=200, the first chunk is the grid cell 200..219, so fail that once
        val (pager, advanceFully) = buildPager(this, failingChunkStartOnce = 200)

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        // Trigger load that will fail once
        latest!!.data[200]
        advanceFully(0)

        val afterError = latest!!
        val errorState = afterError.loadState.shouldBeInstanceOf<LoadState.Error>()
        errorState.key shouldBe 200

        // Retry via PagingData.retry with the key the error reported - the documented recovery path
        afterError.retry(errorState.key)
        advanceFully(0)

        val afterRetry = latest!!
        afterRetry.loadState.shouldBeInstanceOf<LoadState.Success>()
        // Ensure requested item is now present
        val entry = afterRetry.data[200].shouldBeInstanceOf<EntryState.Success<Int>>()
        entry.value shouldBe 200

        job.cancel()
    }

    @Test
    fun retry_reloads_without_waiting_for_the_key_debounce() = runTest {
        val (pager, _) = buildPager(this, failingChunkStartOnce = 200)

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        latest!!.data[200]
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.advanceUntilIdle()
        latest!!.loadState.shouldBeInstanceOf<LoadState.Error>()

        val retriedAt = this.testScheduler.currentTime
        latest!!.retry(200)
        this.testScheduler.runCurrent()

        // The reload is scheduled straight away rather than after another debounce window
        latest!!.loadState.shouldBeInstanceOf<LoadState.Success>()
        this.testScheduler.currentTime shouldBe retriedAt
        latest!!.data[200].shouldBeInstanceOf<EntryState.Success<Int>>()

        job.cancel()
    }

    @Test
    fun refresh_reloads_the_window_around_the_last_accessed_key() = runTest {
        val starts = mutableListOf<Int>()
        val pager = Pager<Int>(loadSize = 20, preloadSize = 20, cacheSize = 100) { pos, size ->
            flow {
                starts += pos
                val values: Map<Int, Int> = (pos..<pos + size).associateWith { it }
                emit(DataPortion(totalSize = 1_000, values = values.toPersistentMap()))
            }
        }

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        latest!!.data[500]
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.advanceUntilIdle()
        starts.clear()

        pager.refresh()
        this.testScheduler.advanceUntilIdle()

        // Reloaded around 500 - the position the user is actually looking at - not around 0
        starts.isEmpty() shouldBe false
        starts.all { it in 400..600 } shouldBe true
        latest!!.data[500].shouldBeInstanceOf<EntryState.Success<Int>>()
        latest!!.loadState.shouldBeInstanceOf<LoadState.Success>()

        job.cancel()
    }

    @Test
    fun refresh_does_not_let_an_in_flight_load_restore_cleared_values() = runTest {
        var epoch = 0
        val pager = Pager<String>(loadSize = 20, preloadSize = 20, cacheSize = 100) { pos, size ->
            flow {
                val fetchedIn = epoch
                delay(50)
                val values: Map<Int, String> = (pos..<pos + size).associateWith { "e$fetchedIn-$it" }
                emit(DataPortion(totalSize = 1_000, values = values.toPersistentMap()))
            }
        }

        var latest: PagingData<String>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        latest!!.data[500]
        this.testScheduler.advanceTimeBy(300) // debounce elapsed, first chunk now in flight
        this.testScheduler.advanceTimeBy(25)

        epoch = 1
        pager.refresh()
        this.testScheduler.advanceUntilIdle()

        val values = latest!!.data.values.values
        values.isEmpty() shouldBe false
        values.none { it.startsWith("e0-") } shouldBe true

        job.cancel()
    }

    @Test
    fun initial_load_is_not_debounced() = runTest {
        val startedAt = mutableListOf<Long>()
        val pager = Pager<Int>(loadSize = 20, preloadSize = 20, cacheSize = 100) { pos, size ->
            flow {
                startedAt += this@runTest.testScheduler.currentTime
                val values: Map<Int, Int> = (pos..<pos + size).associateWith { it }
                emit(DataPortion(totalSize = 1_000, values = values.toPersistentMap()))
            }
        }

        val job: Job = launch { pager.flow.collectLatest { } }
        this.testScheduler.advanceUntilIdle()

        startedAt shouldBe listOf(0L)

        job.cancel()
    }

    @Test
    fun key_debounce_is_configurable() = runTest {
        val startedAt = mutableListOf<Long>()
        val pager = Pager<Int>(loadSize = 20, preloadSize = 20, cacheSize = 100, keyDebounceMs = 0) { pos, size ->
            flow {
                startedAt += this@runTest.testScheduler.currentTime
                delay(100)
                val values: Map<Int, Int> = (pos..<pos + size).associateWith { it }
                emit(DataPortion(totalSize = 1_000, values = values.toPersistentMap()))
            }
        }

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()
        startedAt.clear()
        val jumpedAt = this.testScheduler.currentTime

        latest!!.data[500]
        this.testScheduler.advanceUntilIdle()

        // With the debounce switched off the jump is served immediately
        startedAt.first() shouldBe jumpedAt

        job.cancel()
    }

    @Test
    fun rejects_a_negative_key_debounce() {
        shouldThrow<IllegalArgumentException> {
            Pager<Int>(keyDebounceMs = -1) { _, _ -> emptyFlow() }
        }
    }

    @Test
    fun initial_key_zero_requests_full_load_size_chunk() = runTest {
        val callArgs = mutableListOf<Pair<Int, Int>>()
        val pager = Pager<Int>(
            loadSize = 5,
            preloadSize = 5,
            cacheSize = 20,
            readData = { pos, size ->
                flow {
                    callArgs += pos to size
                    val last = (pos + size - 1).coerceAtMost(20)
                    val values: Map<Int, Int> = (pos..last).associateWith { it }
                    emit(DataPortion(totalSize = 20, values = values.toPersistentMap()))
                }
            },
        )

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        checkNotNull(latest).data[0]
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.advanceUntilIdle()

        callArgs.distinct() shouldBe listOf(0 to 5)
        latest.data[4].shouldBeInstanceOf<EntryState.Success<Int>>()

        job.cancel()
    }

    @Test
    fun fetch_ranges_are_snapped_to_a_load_size_grid() = runTest {
        val loadSize = 20
        val calls = mutableListOf<Pair<Int, Int>>()
        val pager = Pager<Int>(loadSize = loadSize, preloadSize = 60, cacheSize = 100) { pos, size ->
            flow {
                calls += pos to size
                val values: Map<Int, Int> = (pos..<pos + size).associateWith { it }
                emit(DataPortion(totalSize = 1_000, values = values.toPersistentMap()))
            }
        }

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        val visit: (Int) -> Unit = { key ->
            latest!!.data[key]
            this.testScheduler.advanceTimeBy(300)
            this.testScheduler.advanceUntilIdle()
        }

        // A key in the middle of a grid cell must not shift the grid
        calls.clear()
        visit(507)
        val aroundKey = calls.sortedBy { it.first }
        calls.clear()

        // Move far enough for the window around 507 to be evicted, then come back
        visit(900)
        calls.clear()
        visit(507)

        // Same positions, same requests: an HTTP or CDN cache can serve the second visit
        aroundKey.isEmpty() shouldBe false
        calls.sortedBy { it.first } shouldBe aroundKey
        aroundKey.all { (pos, size) -> pos % loadSize == 0 && size == loadSize } shouldBe true
        aroundKey.map { it.first }.distinct() shouldBe aroundKey.map { it.first }

        job.cancel()
    }

    @Test
    fun rejects_a_cache_narrower_than_the_preload_window() {
        val error = shouldThrow<IllegalArgumentException> {
            Pager<Int>(loadSize = 20, preloadSize = 100, cacheSize = 40) { _, _ -> emptyFlow() }
        }

        val message = error.message.orEmpty()
        message shouldContain "cacheSize (40)"
        message shouldContain "preloadSize (100)"
    }

    @Test
    fun accepts_a_cache_exactly_as_wide_as_the_preload_window() {
        shouldNotThrowAny {
            Pager<Int>(loadSize = 20, preloadSize = 100, cacheSize = 100) { _, _ -> emptyFlow() }
        }
    }

    @Test
    fun rejects_a_non_positive_load_size() {
        shouldThrow<IllegalArgumentException> {
            Pager<Int>(loadSize = 0) { _, _ -> emptyFlow() }
        }
    }
}
