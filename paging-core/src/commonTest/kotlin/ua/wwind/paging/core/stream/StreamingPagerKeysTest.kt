package ua.wwind.paging.core.stream

import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ua.wwind.paging.core.ExperimentalStreamingPagerApi
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.core.PagingMap
import kotlin.test.Test

private typealias Row = Pair<String, Int>

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStreamingPagerApi::class)
class StreamingPagerKeysTest {

    private class TestSource<T> {
        val totalFlow = MutableStateFlow(0)
        private val portionFlows: MutableMap<Pair<Int, Int>, MutableSharedFlow<Map<Int, T>>> = LinkedHashMap()

        fun readTotal(): Flow<Int> = totalFlow

        fun readPortion(start: Int, size: Int): Flow<Map<Int, T>> =
            portionFlows.getOrPut(start to size) { MutableSharedFlow(replay = 1) }

        suspend fun emitPortion(start: Int, size: Int, values: Map<Int, T>) {
            portionFlows[start to size]?.emit(values)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun resetReplay(start: Int, size: Int) {
            portionFlows[start to size]?.resetReplayCache()
        }

        fun subscribers(start: Int, size: Int): Int = portionFlows[start to size]?.subscriptionCount?.value ?: 0

        fun subscribedRanges(): Set<Pair<Int, Int>> =
            portionFlows.filter { it.value.subscriptionCount.value > 0 }.keys.toSet()
    }

    private val config = StreamingPagerConfig(
        loadSize = 5,
        preloadSize = 5,
        cacheSize = 100,
        closeThreshold = 5,
        keyDebounceMs = 0,
    )

    private fun pager(source: TestSource<Row>, itemKey: ((Row) -> Any)? = { it.first }) = StreamingPager(
        config = config,
        readTotal = { source.readTotal() },
        itemKey = itemKey,
    ) { s, sz -> source.readPortion(s, sz) }

    /** Rows for [ids] (one char each) starting at [start]; the row's int is its position. */
    private fun rows(start: Int, ids: String): Map<Int, Row> =
        ids.mapIndexed { i, c -> (start + i) to (c.toString() to start + i) }.toMap()

    private class Collected(val snapshots: MutableList<PagingData<Row>>, val job: Job) {
        val latest: PagingData<Row> get() = snapshots.last()
    }

    private fun TestScope.collect(pager: StreamingPager<Row>): Collected {
        val snapshots = mutableListOf<PagingData<Row>>()
        val job = launch { pager.flow.collect { snapshots += it } }
        return Collected(snapshots, job)
    }

    private fun assertKeysUnique(snapshot: PagingData<Row>) {
        val data = snapshot.data
        val keys = (0 until data.size).map { data.keyAt(it) }
        keys.toSet().size shouldBe keys.size
    }

    private fun assertUnique(snapshot: PagingData<Row>) {
        assertKeysUnique(snapshot)
        val ids = snapshot.data.values.values.map { it.first }
        ids.toSet().size shouldBe ids.size
    }

    private fun Collected.assertAllUnique() = snapshots.forEach(::assertUnique)

    /** A confirmed real duplicate shows its item twice, so ids are only unique before [confirmedAt]. */
    private fun Collected.assertUniqueUntil(confirmedAt: Int) {
        snapshots.take(confirmedAt).forEach(::assertUnique)
        snapshots.forEach(::assertKeysUnique)
    }

    private fun Collected.assertShows(ids: String) {
        val data = latest.data
        ids.forEachIndexed { i, c ->
            data.values[i].shouldNotBeNull().first shouldBe c.toString()
            data.keyAt(i) shouldBe c.toString()
        }
    }

    @Test
    fun deleteAbove_lowerWindowStale_neverDuplicates() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcde"))
        src.emitPortion(5, 5, rows(5, "fghij"))
        runCurrent()

        src.emitPortion(0, 5, rows(0, "bcdef"))
        runCurrent()
        c.latest.data.keyAt(4) shouldBe "f"
        c.latest.data.values shouldNotContainKey 5
        c.latest.data.keyAt(5) shouldBe PagingMap.FALLBACK_KEY_PREFIX + 5

        src.emitPortion(5, 5, rows(5, "ghijk"))
        runCurrent()
        c.assertShows("bcdefghijk")
        c.assertAllUnique()
        c.job.cancel()
    }

    @Test
    fun insertAbove_lowerWindowFirst_neverDuplicates() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcde"))
        src.emitPortion(5, 5, rows(5, "fghij"))
        runCurrent()

        src.emitPortion(5, 5, rows(5, "efghi"))
        runCurrent()
        c.latest.data.keyAt(5) shouldBe "e"
        c.latest.data.values shouldNotContainKey 4

        src.emitPortion(0, 5, rows(0, "zabcd"))
        runCurrent()
        c.assertShows("zabcdefghi")
        c.assertAllUnique()
        c.job.cancel()
    }

    @Test
    fun itemMovesBetweenWindows_neverDuplicates() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcde"))
        src.emitPortion(5, 5, rows(5, "fghij"))
        runCurrent()

        src.emitPortion(5, 5, rows(5, "ghcij"))
        runCurrent()
        c.latest.data.keyAt(7) shouldBe "c"
        c.latest.data.values shouldNotContainKey 2

        src.emitPortion(0, 5, rows(0, "abdef"))
        runCurrent()
        c.assertShows("abdefghcij")
        c.assertAllUnique()
        c.job.cancel()
    }

    @Test
    fun closedWindow_hiddenPosition_reopensOnRead() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 100
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcde"))
        src.emitPortion(5, 5, rows(5, "fghij"))
        runCurrent()
        src.emitPortion(0, 5, rows(0, "bcdef"))
        runCurrent()
        c.latest.data.values shouldNotContainKey 5

        c.latest.data[50]
        runCurrent()
        src.subscribers(5, 5) shouldBe 0
        // The grace period passes while the window is closed: the position stays hidden.
        advanceUntilIdle()
        c.latest.data.values shouldNotContainKey 5
        c.latest.data.keyAt(5) shouldBe PagingMap.FALLBACK_KEY_PREFIX + 5

        src.resetReplay(5, 5)
        c.latest.data[5]
        runCurrent()
        src.subscribers(5, 5) shouldBe 1
        c.latest.data.values shouldNotContainKey 5

        src.emitPortion(5, 5, rows(5, "ghijk"))
        runCurrent()
        c.latest.data.keyAt(5) shouldBe "g"
        c.latest.data.keyAt(4) shouldBe "f"
        c.assertAllUnique()
        c.job.cancel()
    }

    @Test
    fun realDuplicate_liveWindowSilent_confirmedAfterGrace() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcdu"))
        src.emitPortion(5, 5, rows(5, "ufghi"))
        runCurrent()
        c.latest.data.values shouldNotContainKey 4

        advanceTimeBy(CONFLICT_GRACE_MS - 1)
        runCurrent()
        c.latest.data.values shouldNotContainKey 4

        val beforeConfirm = c.snapshots.size
        advanceTimeBy(1)
        runCurrent()
        c.latest.data.values[4].shouldNotBeNull().first shouldBe "u"
        c.latest.data.values[5].shouldNotBeNull().first shouldBe "u"
        c.latest.data.keyAt(5) shouldBe "u"
        c.assertUniqueUntil(beforeConfirm)
        c.job.cancel()
    }

    @Test
    fun realDuplicate_loserWindowReemits_confirmedImmediately() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcdu"))
        src.emitPortion(5, 5, rows(5, "ufghi"))
        runCurrent()
        c.latest.data.values shouldNotContainKey 4

        val beforeConfirm = c.snapshots.size
        src.emitPortion(0, 5, rows(0, "abcdu"))
        runCurrent()
        c.latest.data.values[4].shouldNotBeNull().first shouldBe "u"
        c.latest.data.values[5].shouldNotBeNull().first shouldBe "u"
        c.assertUniqueUntil(beforeConfirm)
        c.job.cancel()
    }

    @Test
    fun shrinkWhileHidden_dropsHiddenPositionsCleanly() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 10
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(5, 5, rows(5, "fghic"))
        src.emitPortion(0, 5, rows(0, "abcde"))
        runCurrent()
        c.latest.data.values shouldNotContainKey 9

        src.totalFlow.value = 8
        runCurrent()
        c.latest.data.size shouldBe 8
        c.latest.data.values.keys.all { it < 8 } shouldBe true
        c.latest.data.keyAt(2) shouldBe "c"

        src.emitPortion(0, 5, rows(0, "abxcd"))
        runCurrent()
        c.latest.data.keyAt(3) shouldBe "c"
        c.latest.data.values[3].shouldNotBeNull().first shouldBe "c"
        c.assertAllUnique()
        c.job.cancel()
    }

    @Test
    fun intIds_noFallbackCollision() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src, itemKey = { it.second }))
        advanceUntilIdle()
        // Row at position i carries id 9 - i, i.e. another row's position.
        src.emitPortion(0, 5, (0..4).associateWith { "r$it" to 9 - it })
        src.emitPortion(5, 5, (5..9).associateWith { "r$it" to 9 - it })
        runCurrent()
        c.latest.data.keyAt(0) shouldBe 9
        c.latest.data.keyAt(15) shouldBe PagingMap.FALLBACK_KEY_PREFIX + 15
        c.assertAllUnique()
        c.job.cancel()
    }

    @Test
    fun keyAt_overWideSpan_doesNotMoveWindow() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 300
        val c = collect(pager(src))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcde"))
        src.emitPortion(5, 5, rows(5, "fghij"))
        advanceUntilIdle()
        val before = src.subscribedRanges()

        val data = c.latest.data
        for (i in 0..249) data.keyAt(i)
        advanceUntilIdle()
        src.subscribedRanges() shouldBe before
        c.job.cancel()
    }

    @Test
    fun withoutItemKey_keyAtIsFallbackOnly() = runTest {
        val src = TestSource<Row>()
        src.totalFlow.value = 20
        val c = collect(pager(src, itemKey = null))
        advanceUntilIdle()
        src.emitPortion(0, 5, rows(0, "abcde"))
        src.emitPortion(5, 5, rows(5, "abcde"))
        advanceUntilIdle()
        val data = c.latest.data
        data.values.size shouldBe 10
        (0 until data.size).forEach { data.keyAt(it) shouldBe PagingMap.FALLBACK_KEY_PREFIX + it }
        c.job.cancel()
    }
}
