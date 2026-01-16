package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
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
            }
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
        val cacheSize = 40
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
        // With loadSize 20 and key=200, first chunk is 190..209, so make that fail once
        val (pager, advanceFully) = buildPager(this, failingChunkStartOnce = 190)

        var latest: PagingData<Int>? = null
        val job: Job = launch { pager.flow.collectLatest { latest = it } }
        this.testScheduler.advanceUntilIdle()

        // Trigger load that will fail once
        latest!!.data[200]
        advanceFully(0)

        val afterError = latest!!
        val errorState = afterError.loadState.shouldBeInstanceOf<LoadState.Error>()
        errorState.key shouldBe 200

        // Retry via PagingData.retry; use a nearby key to bypass distinctUntilChanged
        afterError.retry(201)
        advanceFully(0)

        val afterRetry = latest!!
        afterRetry.loadState.shouldBeInstanceOf<LoadState.Success>()
        // Ensure requested item is now present
        val entry = afterRetry.data[200].shouldBeInstanceOf<EntryState.Success<Int>>()
        entry.value shouldBe 200

        job.cancel()
    }
}
