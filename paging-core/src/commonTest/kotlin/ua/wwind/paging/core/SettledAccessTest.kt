package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.stream.StreamingPager
import ua.wwind.paging.core.stream.StreamingPagerConfig
import kotlin.test.Test

/**
 * A list re-reads every visible row on each recomposition, so `PagingMap.get` runs per row per
 * frame. Once the window around the reader is loaded, none of those reads can change what the pager
 * would do, and the key trigger behind them - a `MutableStateFlow` CAS that also restarts the
 * debounce - is pure overhead (#16).
 *
 * These tests pin down the resulting contract: reads deep inside the loaded window are inert, reads
 * within one page of its edge still move the window, and anything that invalidates the cache makes
 * every read count again.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class SettledAccessTest {

    // ---------------------------------------------------------------------------------- Pager

    private fun pager(
        starts: MutableList<Int>,
        totalSize: Int = 1_000,
        loadSize: Int = 20,
        preloadSize: Int = 60,
        cacheSize: Int = 100,
        failFirstAt: Int? = null,
    ): Pager<Int> {
        var failed = false
        return Pager(loadSize, preloadSize, cacheSize) { pos, size ->
            flow {
                starts += pos
                if (pos == failFirstAt && !failed) {
                    failed = true
                    error("simulated failure at $pos")
                }
                val last = (pos + size - 1).coerceAtMost(totalSize - 1)
                emit(DataPortion(totalSize, (pos..last).associateWith { it }.toPersistentMap()))
            }
        }
    }

    /** Settles the pager on [key] - the first pass only learns the total size, the second fills the window. */
    private suspend fun TestScope.settleAt(latest: () -> PagingData<Int>, key: Int) {
        latest().data[key]
        testScheduler.advanceUntilIdle()
        latest().data[key]
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun reads_inside_the_loaded_window_issue_no_requests() = runTest {
        val starts = mutableListOf<Int>()
        var latest: PagingData<Int>? = null
        val job = launch { pager(starts).flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        settleAt({ checkNotNull(latest) }, 500)
        // preloadSize = 60 around 500, snapped to the loadSize grid
        checkNotNull(latest).data.firstKey() shouldBe 440
        latest.data.lastKey() shouldBe 559
        val settled = starts.size

        // Everything a viewport anywhere in the interior of that window would read
        (460..539).forEach { key ->
            latest.data[key].shouldBeInstanceOf<EntryState.Success<Int>>()
        }
        testScheduler.advanceUntilIdle()

        starts.size shouldBe settled
        job.cancel()
    }

    @Test
    fun reads_within_a_page_of_the_window_edge_recentre_it() = runTest {
        val starts = mutableListOf<Int>()
        var latest: PagingData<Int>? = null
        val job = launch { pager(starts).flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        settleAt({ checkNotNull(latest) }, 500)
        val settled = starts.size

        // 545 is still cached, but only 14 rows short of the loaded edge
        checkNotNull(latest).data[545].shouldBeInstanceOf<EntryState.Success<Int>>()
        testScheduler.advanceTimeBy(300)
        testScheduler.advanceUntilIdle()

        (starts.size > settled) shouldBe true
        latest.data.lastKey() shouldBe 619
        job.cancel()
    }

    @Test
    fun a_read_at_the_data_set_edge_stays_inert() = runTest {
        val starts = mutableListOf<Int>()
        var latest: PagingData<Int>? = null
        val job = launch { pager(starts, totalSize = 50).flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        settleAt({ checkNotNull(latest) }, 25)
        // The whole data set fits in the window, so both edges are edges of the data set
        checkNotNull(latest).data.values.size shouldBe 50
        val settled = starts.size

        (0..49).forEach { key -> latest.data[key] }
        testScheduler.advanceUntilIdle()

        starts.size shouldBe settled
        job.cancel()
    }

    @Test
    fun refresh_reloads_a_settled_window() = runTest {
        val starts = mutableListOf<Int>()
        val pager = pager(starts)
        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        settleAt({ checkNotNull(latest) }, 500)
        val settled = starts.size

        // The position it reloads around comes from the key trigger, which the gate keeps quiet
        pager.refresh()
        testScheduler.advanceUntilIdle()

        (starts.size > settled) shouldBe true
        checkNotNull(latest).data[500].shouldBeInstanceOf<EntryState.Success<Int>>()
        job.cancel()
    }

    @Test
    fun a_window_that_failed_to_load_keeps_reads_live() = runTest {
        val starts = mutableListOf<Int>()
        var latest: PagingData<Int>? = null
        // The chunk holding 500 is fetched first and fails, so the pass never settles
        val job = launch { pager(starts, failFirstAt = 500).flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        settleAt({ checkNotNull(latest) }, 500)
        checkNotNull(latest).loadState.shouldBeInstanceOf<LoadState.Error>()
        latest.data[500].shouldBeInstanceOf<EntryState.Loading>()
        val afterFailure = starts.size

        // Nothing around the failure is settled, so the next row that scrolls into view still counts
        latest.data[480]
        testScheduler.advanceTimeBy(300)
        testScheduler.advanceUntilIdle()

        (starts.size > afterFailure) shouldBe true
        job.cancel()
    }

    // ------------------------------------------------------------------------- StreamingPager

    private class Stream(total: Int) {
        val items = MutableStateFlow(List(total) { "v-$it" })
        val opens = mutableListOf<Pair<Int, Int>>()

        fun total(): Flow<Int> = items.map { it.size }.distinctUntilChanged()

        fun portion(start: Int, size: Int): Flow<Map<Int, String>> = flow {
            opens += start to size
            items.map { list ->
                val end = (start + size).coerceAtMost(list.size)
                if (start < end) (start until end).associateWith { list[it] } else emptyMap()
            }.distinctUntilChanged().collect { emit(it) }
        }
    }

    @Test
    fun streaming_reads_inside_the_window_open_no_streams() = runTest {
        val stream = Stream(1_000)
        val pager = StreamingPager(
            config = StreamingPagerConfig(loadSize = 20, preloadSize = 60, cacheSize = 200),
            readTotal = { stream.total() },
            readPortion = { pos, size -> stream.portion(pos, size) },
        )
        var latest: PagingData<String>? = null
        val job = launch { pager.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        checkNotNull(latest).data[500]
        testScheduler.advanceUntilIdle()
        val opened = stream.opens.size

        (latest.data.firstKey() + 20..latest.data.lastKey() - 20).forEach { key ->
            latest.data[key].shouldBeInstanceOf<EntryState.Success<String>>()
        }
        testScheduler.advanceUntilIdle()
        stream.opens.size shouldBe opened

        // ...while a read near the edge still shifts the window
        latest.data[latest.data.lastKey() - 5]
        testScheduler.advanceUntilIdle()
        (stream.opens.size > opened) shouldBe true

        job.cancel()
    }
}
