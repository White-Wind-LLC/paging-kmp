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
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.stream.StreamingPager
import ua.wwind.paging.core.stream.StreamingPagerConfig
import kotlin.test.Test

/**
 * A stationary consumer must leave the window where it is, however wide the span it reads (#45).
 *
 * The one modelled here is a `LazyColumn` resolving an item for every key of its nearby range and
 * not only for the rows on screen, so its span is wider than the window - which used to make the
 * two ends of that span evict each other in turn, flipping the window between them forever.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class WindowOscillationTest {

    private companion object {
        const val TOTAL = 12_727

        /** Positions a `LazyColumn` at the top of the list reads on each emission. */
        const val NEARBY_SPAN = 130
        const val VISIBLE_ROWS = 21
    }

    /** The nearby range is resolved first, then the rows on screen - which is where the viewport is. */
    private fun PagingMap<*>.readOneFrame() {
        repeat(NEARBY_SPAN) { this[it] }
        repeat(VISIBLE_ROWS) { this[it] }
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
    fun streaming_a_read_span_wider_than_the_window_settles() = runTest {
        val stream = Stream(TOTAL)
        val pager = StreamingPager(
            config = StreamingPagerConfig(),
            readTotal = { stream.total() },
            readPortion = { pos, size -> stream.portion(pos, size) },
        )
        var latest: PagingData<String>? = null
        val job = launch { pager.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        // Long enough for the window to have reached wherever it is going
        repeat(6) {
            checkNotNull(latest).data.readOneFrame()
            testScheduler.advanceTimeBy(1_000)
            testScheduler.advanceUntilIdle()
        }
        val settledOpens = stream.opens.size

        // Same span, nothing changed - nothing may be requested again
        repeat(6) {
            checkNotNull(latest).data.readOneFrame()
            testScheduler.advanceTimeBy(1_000)
            testScheduler.advanceUntilIdle()
        }

        stream.opens.size shouldBe settledOpens
        // ...and it is the rows on screen that ended up loaded
        checkNotNull(latest).data.values[0].shouldBeInstanceOf<String>()
        latest.data.values[VISIBLE_ROWS - 1].shouldBeInstanceOf<String>()

        job.cancel()
    }

    // --------------------------------------------------------------------------------- Pager

    @Test
    fun a_read_span_wider_than_the_window_settles() = runTest {
        val starts = mutableListOf<Int>()
        val pager = Pager<Int>(readData = { pos, size ->
            flow {
                starts += pos
                val last = (pos + size - 1).coerceAtMost(TOTAL - 1)
                emit(DataPortion(TOTAL, (pos..last).associateWith { it }.toPersistentMap()))
            }
        })
        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collectLatest { latest = it } }
        testScheduler.advanceUntilIdle()

        repeat(6) {
            checkNotNull(latest).data.readOneFrame()
            testScheduler.advanceTimeBy(1_000)
            testScheduler.advanceUntilIdle()
        }
        val settledStarts = starts.size

        repeat(6) {
            checkNotNull(latest).data.readOneFrame()
            testScheduler.advanceTimeBy(1_000)
            testScheduler.advanceUntilIdle()
        }

        starts.size shouldBe settledStarts
        checkNotNull(latest).data.values[0].shouldBeInstanceOf<Int>()
        latest.data.values[VISIBLE_ROWS - 1].shouldBeInstanceOf<Int>()

        job.cancel()
    }
}
