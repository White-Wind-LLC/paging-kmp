package ua.wwind.paging.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
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
    }

    private fun buildPager(
        scope: TestScope,
        config: StreamingPagerConfig = StreamingPagerConfig(
            loadSize = 5,
            preloadSize = 5,
            cacheSize = 100,
            closeThreshold = 5,
            keyDebounceMs = 0
        ),
        source: TestSource<Int>,
    ): Pair<StreamingPager<Int>, suspend (Int) -> Unit> {
        val dispatched = StandardTestDispatcher(scope.testScheduler)

        val pager: StreamingPager<Int> = StreamingPager(
            config = config,
            scope = TestScope(dispatched),
            readTotal = { source.readTotal() },
            readPortion = { s, sz -> source.readPortion(s, sz) }
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
        assertEquals(0, latest?.data?.size ?: 0)

        // Update total
        src.totalFlow.value = 50
        advanceFully(100)
        assertEquals(50, latest?.data?.size)

        job.cancel()
    }

    @Test
    fun loads_portion_on_access_and_merges_values() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 50
        val (pager, advanceFully) = buildPager(
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 300
            ),
            scope = this,
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }

        // Trigger access for key 1 -> should request portion [1..5]
        val initial = latest ?: pager.flow.first()
        initial.data[1] // triggers onGet
        advanceFully(350) // debounce

        // Ensure portion flow created
        // Emit values for [1..5]
        src.emitPortion(1, 5, (1..5).associateWith { it })
        advanceFully(10)

        val after = latest
        assertNotNull(after)
        val entry = after!!.data[1]
        assertIs<EntryState.Success<Int>>(entry)
        assertEquals(1, entry.value)

        job.cancel()
    }

    @Test
    fun total_shrink_prunes_out_of_bounds() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 20
        val (pager, advanceFully) = buildPager(
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 0
            ),
            scope = this,
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(50)

        // Load [1..10]
        latest!!.data[1]
        advanceFully(50)
        src.emitPortion(1, 5, (1..5).associateWith { it })
        advanceFully(10)
        latest!!.data[8]
        advanceFully(50)
        src.emitPortion(6, 5, (6..10).associateWith { it })
        advanceFully(10)

        // Now shrink total to 7 -> keys > 7 must be pruned
        src.totalFlow.value = 7
        advanceFully(10)
        assertEquals(7, latest!!.data.size)
        // lastKey should be <= 7
        assertEquals(true, latest!!.data.lastKey() <= 7)

        job.cancel()
    }

    @Test
    fun loadState_loading_then_success_when_new_range_opens() = runTest {
        val src = TestSource<Int>()
        src.totalFlow.value = 50
        val (pager, advanceFully) = buildPager(
            config = StreamingPagerConfig(
                loadSize = 5,
                preloadSize = 5,
                cacheSize = 100,
                closeThreshold = 5,
                keyDebounceMs = 300
            ),
            scope = this,
            source = src,
        )

        var latest: PagingData<Int>? = null
        val job = launch { pager.flow.collect { latest = it } }
        advanceFully(350)

        // Open first range [1..5]
        latest!!.data[2]
        advanceFully(350)
        src.emitPortion(1, 5, (1..5).associateWith { it })
        src.emitPortion(6, 5, (1..5).associateWith { it })
        advanceFully(100)
        assertEquals(LoadState.Success, latest!!.loadState)

        // Access far key to open another range [16..20]
        latest!!.data[20]
        advanceFully(350)
        // Before emission, global state should be Loading
        assertEquals(LoadState.Loading, latest!!.loadState)

        // Emit surrounded ranges
        src.emitPortion(11, 5, (16..20).associateWith { it })
        src.emitPortion(16, 5, (16..20).associateWith { it })
        src.emitPortion(21, 5, (16..20).associateWith { it })
        advanceFully(10)
        assertEquals(LoadState.Success, latest!!.loadState)

        job.cancel()
    }
}
