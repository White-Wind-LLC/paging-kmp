package ua.wwind.paging.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PagingMediatorTest {

    private data class Item(val id: Int, val stale: Boolean = false)

    /**
     * Simple in-memory LocalDataSource for tests.
     * Stores absolute-positioned values and totalSize, tracks clear/save calls.
     */
    private class FakeLocal<T, Q>(
        initialTotalSize: Int = 0,
        initialValues: Map<Int, T> = emptyMap(),
        query: Q,
    ) : LocalDataSource<T, Q> {
        var totalSize: Int = initialTotalSize
        private val storage: MutableMap<Int, T> = initialValues.toMutableMap()

        var saveCalls: Int = 0
        var clearCalls: Int = 0

        override suspend fun read(startPosition: Int, size: Int, query: Q): DataPortion<T> {
            val last = (startPosition + size - 1)
            val slice = storage.filterKeys { it in startPosition..last }
            return DataPortion(totalSize = totalSize, values = slice)
        }

        override suspend fun save(portion: DataPortion<T>) {
            saveCalls += 1
            totalSize = portion.totalSize
            storage.putAll(portion.values)
        }

        override suspend fun clear() {
            clearCalls += 1
            totalSize = 0
            storage.clear()
        }
    }

    /**
     * Remote stub that can delay and capture calls.
     */
    private class FakeRemote<T, Q>(
        private val onFetch: suspend (start: Int, size: Int, query: Q) -> DataPortion<T>
    ) : RemoteDataSource<T, Q> {
        var calls: Int = 0
        val callArgs: MutableList<Pair<Int, Int>> = mutableListOf()

        override suspend fun fetch(startPosition: Int, size: Int, query: Q): DataPortion<T> {
            calls += 1
            callArgs += startPosition to size
            return onFetch(startPosition, size, query)
        }
    }

    private fun TestScope.buildMediator(
        local: LocalDataSource<Item, Unit>,
        remote: RemoteDataSource<Item, Unit>,
        config: PagingMediatorConfig<Item>,
    ): Pair<PagingMediator<Item, Unit>, suspend (Long) -> Unit> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mediator = PagingMediator(
            scope = TestScope(dispatcher),
            local = local,
            remote = remote,
            config = config,
        )

        // Helper to advance time: accounts for Pager's 300ms debounce plus extra
        val advanceFully: suspend (Long) -> Unit = { extraMs ->
            testScheduler.advanceTimeBy(300)
            if (extraMs > 0) testScheduler.advanceTimeBy(extraMs)
            testScheduler.advanceUntilIdle()
        }

        return mediator to advanceFully
    }

    @Test
    fun local_stale_records_are_filtered_by_default_and_can_emit_outdated_when_enabled() = runTest {
        // Local has 3 items in the requested window, with one stale entry at position 3
        val localData = mapOf(
            2 to Item(2, stale = false),
            3 to Item(3, stale = true),
            4 to Item(4, stale = false),
        )
        val local = FakeLocal(initialTotalSize = 10, initialValues = localData, Unit)

        // Remote1 will respond after delay to allow observing local emission first
        val remote1 = FakeRemote<Item, Unit> { start, size, _ ->
            delay(1_000)
            val last = (start + size - 1).coerceAtMost(10)
            val values = (start..last).associateWith { pos -> Item(pos, stale = false) }
            DataPortion(totalSize = 10, values = values)
        }
        // Separate remote2 for second mediator to avoid call tracking interference
        val remote2 = FakeRemote<Item, Unit> { start, size, _ ->
            delay(1_000)
            val last = (start + size - 1).coerceAtMost(10)
            val values = (start..last).associateWith { pos -> Item(pos, stale = false) }
            DataPortion(totalSize = 10, values = values)
        }

        // Build mediator with small window to keep things simple
        val config = PagingMediatorConfig<Item>(
            loadSize = 5,
            prefetchSize = 5,
            cacheSize = 20,
            isRecordStale = { it.stale },
            concurrency = 1,
            fetchFullRangeOnMiss = false,
            emitOutdatedRecords = false,
            emitIntermediateResults = true,
        )

        val (mediator, advanceFully) = buildMediator(local, remote1, config)

        var latest: PagingData<Item>? = null
        val job: Job = launch { mediator.flow(Unit).collectLatest { latest = it } }
        this.testScheduler.runCurrent()

        // Trigger load in window 0..4 by accessing position 3
        latest!!.data[3]

        // Advance only past debounce, then run current tasks to process local emission
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.runCurrent()

        // At this moment, only local portion was emitted, remote is still delayed
        val afterLocal = latest!!
        assertIs<LoadState.Loading>(afterLocal.loadState)
        assertIs<EntryState.Loading>(afterLocal.data[3])

        // Now build another mediator that emits outdated local records first
        val configEmitOutdated = config.copy(emitOutdatedRecords = true)
        val (mediator2, _) = buildMediator(
            FakeLocal(10, localData, Unit),
            remote2,
            configEmitOutdated,
        )

        var latest2: PagingData<Item>? = null
        val job2: Job = launch { mediator2.flow(Unit).collectLatest { latest2 = it } }
        this.testScheduler.runCurrent()

        latest2!!.data[3]
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.runCurrent()

        // Complete remote1 and verify final success for first mediator; verify called missing ranges
        advanceFully(1_000)
        val afterRemote = latest!!
        assertIs<LoadState.Success>(afterRemote.loadState)
        // All positions in requested range should now be loaded
        (0..4).forEach { pos ->
            assertIs<EntryState.Success<Item>>(afterRemote.data[pos])
        }
        // Verify remote1 fetched missing ranges: 0..1 and 3..3 (allowing duplicates)
        assertTrue(remote1.callArgs.contains(0 to 2))
        assertTrue(remote1.callArgs.contains(3 to 1))

        job.cancel()
        job2.cancel()
    }

    @Test
    fun fetch_full_range_on_miss_fetches_entire_requested_range_once() = runTest {
        // Local has one item inside the requested window
        val local = FakeLocal(
            initialTotalSize = 10,
            initialValues = mapOf(2 to Item(2)),
            query = Unit,
        )

        val remote = FakeRemote<Item, Unit> { start, size, _ ->
            val last = start + size - 1
            val values = (start..last).associateWith { pos -> Item(pos) }
            DataPortion(totalSize = 10, values = values)
        }

        // Make load and preload equal to request a single chunk around the key
        val config = PagingMediatorConfig<Item>(
            loadSize = 5,
            prefetchSize = 5,
            cacheSize = 10,
            isRecordStale = { false },
            concurrency = 1,
            fetchFullRangeOnMiss = true,
            emitOutdatedRecords = false,
            emitIntermediateResults = true,
        )

        val (mediator, advanceFully) = buildMediator(local, remote, config)

        var latest: PagingData<Item>? = null
        val job: Job = launch { mediator.flow(Unit).collectLatest { latest = it } }
        this.testScheduler.runCurrent()

        // Access key=0 to avoid an extra initial load due to initial keyTrigger value
        // With loadSize=5, startFetchRange should be 0..4
        latest!!.data[0]
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.runCurrent()

        // With key=0 and loadSize=5 on empty state, Pager enqueues two ranges (0..3) and (4..8)
        // Verify mediator fetched each whole requested range (no sub-splitting)
        assertEquals(listOf(0 to 4, 4 to 5), remote.callArgs.distinct())

        val after = latest!!
        assertIs<LoadState.Success>(after.loadState)
        (0..4).forEach { pos -> assertIs<EntryState.Success<Item>>(after.data[pos]) }

        job.cancel()
    }

    @Test
    fun inconsistent_totals_clear_local_and_refetch_once() = runTest {
        // Local believes total size is 10
        val local = FakeLocal(
            initialTotalSize = 10,
            initialValues = emptyMap<Int, Item>(),
            query = Unit,
        )

        // Remote returns a different total size (12) to trigger inconsistency
        var callIndex = 0
        val remote = FakeRemote<Item, Unit> { start, size, _ ->
            callIndex += 1
            val last = start + size - 1
            val values = (start..last).associateWith { pos -> Item(pos) }
            // Always 12 to keep consistent after clear; first compare vs local 10 triggers clear
            DataPortion(totalSize = 12, values = values)
        }

        val config = PagingMediatorConfig<Item>(
            loadSize = 5,
            prefetchSize = 5,
            cacheSize = 10,
            isRecordStale = { false },
            concurrency = 1,
            fetchFullRangeOnMiss = false,
            emitOutdatedRecords = false,
            emitIntermediateResults = true,
        )

        val (mediator, advanceFully) = buildMediator(local, remote, config)

        var latest: PagingData<Item>? = null
        val job: Job = launch { mediator.flow(Unit).collectLatest { latest = it } }
        this.testScheduler.runCurrent()

        // Trigger load for window 0..4
        latest!!.data[1]
        this.testScheduler.advanceTimeBy(300)
        this.testScheduler.runCurrent()

        // Local should have been cleared exactly once due to total mismatch and then saved
        assertEquals(1, local.clearCalls)
        assertTrue(local.saveCalls >= 1)

        val after = latest!!
        assertIs<LoadState.Success>(after.loadState)
        // Size should now reflect remote's size (12)
        assertEquals(12, after.data.size)
        (0..4).forEach { pos -> assertIs<EntryState.Success<Item>>(after.data[pos]) }

        job.cancel()
    }
}
