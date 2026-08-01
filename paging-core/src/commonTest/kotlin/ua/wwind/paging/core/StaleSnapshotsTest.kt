package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.stream.StreamingPager
import ua.wwind.paging.core.stream.StreamingPagerConfig
import kotlin.test.Test

/**
 * Tests that a collector slower than the source is not handed a backlog of snapshots (#12).
 *
 * `PagingData` is a complete snapshot of the list, so only the newest one is ever worth rendering.
 * The `channelFlow` behind both pagers buffers 64 of them by default, which is exactly the wrong
 * behaviour for a live stream: a UI that cannot keep up ends up rendering - and immediately
 * discarding - every state the list has been through.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class StaleSnapshotsTest {

    /** One frame of a UI that renders slower than the data arrives. */
    private val frameMs = 1_000L

    @Test
    fun streaming_pager_hands_a_slow_collector_only_the_newest_snapshot() = runTest {
        val totals = MutableStateFlow(50)
        val portions: MutableSharedFlow<Map<Int, Int>> = MutableSharedFlow(extraBufferCapacity = 64)
        val pager: StreamingPager<Int> = StreamingPager(
            config = StreamingPagerConfig(loadSize = 5, preloadSize = 5, cacheSize = 100, keyDebounceMs = 0),
            readTotal = { totals },
            readPortion = { start, _ -> if (start == 0) portions else MutableSharedFlow() },
        )

        val rendered = mutableListOf<Int?>()
        val job = launch {
            pager.flow.collect { paging ->
                rendered += paging.data.values[0]
                delay(frameMs)
            }
        }
        runCurrent() // the first snapshot is taken, the collector is now busy rendering it

        // Five messages arrive while that single frame is being rendered.
        repeat(5) { message ->
            portions.emit((0..4).associateWith { key -> key + 10 * (message + 1) })
            runCurrent()
        }
        advanceUntilIdle()

        // One rendered frame per message would be four wasted ones: they are already outdated.
        rendered.size shouldBe 2
        rendered.last() shouldBe 50

        job.cancel()
    }

    @Test
    fun pager_hands_a_slow_collector_only_the_newest_snapshot() = runTest {
        val portions: MutableSharedFlow<DataPortion<Int>> = MutableSharedFlow(extraBufferCapacity = 64)
        val pager: Pager<Int> = Pager(
            loadSize = 20,
            preloadSize = 0,
            cacheSize = 100,
            keyDebounceMs = 0,
            readData = { _, _ -> portions },
        )

        val rendered = mutableListOf<Int?>()
        val job = launch {
            pager.flow.collect { paging ->
                rendered += paging.data.values[0]
                delay(frameMs)
            }
        }
        runCurrent()

        repeat(5) { message ->
            portions.emit(
                DataPortion(
                    totalSize = 50,
                    values = (0..19).associateWith { key -> key + 10 * (message + 1) }.toPersistentMap(),
                ),
            )
            runCurrent()
        }
        advanceUntilIdle()

        rendered.size shouldBe 2
        rendered.last() shouldBe 50

        job.cancel()
    }
}
