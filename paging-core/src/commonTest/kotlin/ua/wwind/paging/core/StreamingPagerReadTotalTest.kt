package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.stream.StreamingPager
import ua.wwind.paging.core.stream.StreamingPagerConfig
import kotlin.test.Test

/** `readTotal()` outcomes must not overwrite, or be overwritten by, the per-range states (#59). */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class StreamingPagerReadTotalTest {

    private fun quietPager(readTotal: () -> Flow<Int>): StreamingPager<Int> = StreamingPager(
        config = StreamingPagerConfig(
            loadSize = 5,
            preloadSize = 5,
            cacheSize = 100,
            closeThreshold = 5,
            keyDebounceMs = 0,
        ),
        readTotal = readTotal,
        // Emits once and stays live: a retry gets nothing more out of it.
        readPortion = { start, size -> MutableStateFlow((start..<start + size).associateWith { it }) },
    )

    @Test
    fun retry_after_a_read_total_failure_leaves_quiet_streams_settled() = runTest {
        var totalCalls = 0
        val boom = IllegalStateException("total boom")
        val pager = quietPager {
            flow {
                emit(20)
                if (++totalCalls == 1) {
                    delay(100)
                    throw boom
                }
                awaitCancellation()
            }
        }

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        testScheduler.advanceUntilIdle()

        latest?.loadState shouldBe LoadState.Error(boom, 0)
        latest?.prefetchState shouldBe LoadState.Success

        checkNotNull(latest).retry(0)
        testScheduler.advanceUntilIdle()

        totalCalls shouldBe 2
        latest.loadState shouldBe LoadState.Success
        latest.prefetchState shouldBe LoadState.Success

        job.cancel()
    }

    @Test
    fun retry_after_a_finite_read_total_completed_leaves_quiet_streams_settled() = runTest {
        var totalCalls = 0
        val pager = quietPager {
            flow {
                totalCalls++
                emit(20)
            }
        }

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        testScheduler.advanceUntilIdle()
        latest?.prefetchState shouldBe LoadState.Success

        checkNotNull(latest).retry(0)
        testScheduler.advanceUntilIdle()

        totalCalls shouldBe 2
        latest.loadState shouldBe LoadState.Success
        latest.prefetchState shouldBe LoadState.Success

        job.cancel()
    }

    @Test
    fun known_empty_list_is_success_even_if_the_first_page_never_answers() = runTest {
        val pager: StreamingPager<Int> = StreamingPager(
            config = StreamingPagerConfig(loadSize = 5, preloadSize = 5, cacheSize = 100, keyDebounceMs = 0),
            readTotal = { MutableStateFlow(0) },
            readPortion = { _, _ -> flow { awaitCancellation() } },
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        testScheduler.advanceUntilIdle()

        latest?.data?.size shouldBe 0
        latest?.loadState shouldBe LoadState.Success

        job.cancel()
    }
}
