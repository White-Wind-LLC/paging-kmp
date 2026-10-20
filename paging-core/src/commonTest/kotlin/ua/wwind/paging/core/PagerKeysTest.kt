package ua.wwind.paging.core

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private typealias Row = Pair<String, Int>

@OptIn(ExperimentalCoroutinesApi::class)
class PagerKeysTest {

    /** Source backed by a mutable list; delays are scripted per `(start, read number)`. */
    private class ListSource(var list: List<Row>) {
        val reads = mutableMapOf<Int, Int>()

        /** Delay before the list is read, then delay before the portion is emitted. */
        var delays: (start: Int, read: Int) -> Pair<Long, Long> = { _, _ -> 0L to 0L }

        /** Runs right before the list is read. */
        var beforeSnapshot: (start: Int, read: Int) -> Unit = { _, _ -> }

        /** Whether a read completes without emitting. */
        var silent: (start: Int, read: Int) -> Boolean = { _, _ -> false }

        /** Positions a read leaves out of its portion. */
        var omitted: (start: Int, read: Int) -> Set<Int> = { _, _ -> emptySet() }

        val totalReads: Int get() = reads.values.sum()

        fun readData(start: Int, size: Int): Flow<DataPortion<Row>> = flow {
            val read = reads[start] ?: 0
            reads[start] = read + 1
            val (beforeRead, beforeEmit) = delays(start, read)
            delay(beforeRead)
            beforeSnapshot(start, read)
            val snapshot = list
            delay(beforeEmit)
            val end = minOf(start + size, snapshot.size)
            val values = (start until end).associateWith { snapshot[it] } - omitted(start, read)
            if (silent(start, read)) return@flow
            emit(DataPortion(snapshot.size, values.toPersistentMap()))
        }
    }

    private fun pager(source: ListSource, itemKey: ((Row) -> Any)? = { it.first }) = Pager(
        loadSize = 20,
        preloadSize = 21,
        cacheSize = 100,
        keyDebounceMs = 0,
        itemKey = itemKey,
    ) { start, size -> source.readData(start, size) }

    private fun ids(count: Int, prefix: String = "r"): List<Row> = (0 until count).map { "$prefix$it" to it }

    private class Collected(val snapshots: MutableList<PagingData<Row>>, val job: Job) {
        val latest: PagingData<Row> get() = snapshots.last()
    }

    private fun TestScope.collect(pager: Pager<Row>): Collected {
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

    private fun isFallback(key: Any): Boolean = key is String && key.startsWith(PagingMap.FALLBACK_KEY_PREFIX)

    /** First pass loads `0..19`; reading position 1 then plans `0..39`, which fetches `20..39`. */
    private fun TestScope.loadBothPages(c: Collected) {
        advanceUntilIdle()
        c.latest.data[1]
        advanceUntilIdle()
    }

    @Test
    fun staleWinnerPage_resolvedByRefetch_noDuplicateEver() = runTest {
        val source = ListSource(ids(40))
        val pager = pager(source)
        val c = collect(pager)
        advanceUntilIdle()

        source.reads.clear()
        // 20..39 reads before the delete and lands last; 0..19 reads after it.
        source.delays = { start, read ->
            when {
                read > 0 -> 0L to 0L
                start == 0 -> 100L to 0L
                else -> 0L to 200L
            }
        }
        pager.refresh()
        runCurrent()
        advanceTimeBy(50)
        // Delete above both pages; append one so the total stays 40.
        source.list = source.list.filterIndexed { i, _ -> i != 5 } + ("new" to 99)
        advanceUntilIdle()

        c.snapshots.forEach(::assertUnique)
        // The stale page hid position 19 for a while.
        c.snapshots.any { 19 !in it.data.values && 20 in it.data.values && it.data.size == 40 } shouldBe true
        val data = c.latest.data
        for (i in 0 until 40) {
            data.values[i].shouldNotBeNull().first shouldBe source.list[i].first
            data.keyAt(i) shouldBe source.list[i].first
        }
        source.reads[0] shouldBe 2
        source.reads[20] shouldBe 2
        c.job.cancel()
    }

    @Test
    fun realDuplicate_confirmedAfterOneRefetch_noLoop() = runTest {
        val source = ListSource(ids(40).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        val c = collect(pager(source))
        loadBothPages(c)

        c.snapshots.forEach(::assertKeysUnique)
        val data = c.latest.data
        data.values[19].shouldNotBeNull().first shouldBe "u"
        data.values[20].shouldNotBeNull().first shouldBe "u"
        listOf(data.keyAt(19), data.keyAt(20)).count { it == "u" } shouldBe 1
        // 0..19 and 20..39 once each, then both again for the conflict.
        source.totalReads shouldBe 4

        advanceTimeBy(5_000)
        advanceUntilIdle()
        source.totalReads shouldBe 4
        c.job.cancel()
    }

    /** Real duplicate "u" at 19 and 20; r10 and r25 swap during the conflict refetch round. */
    private fun swapDuringRefetchSource(): ListSource {
        val source = ListSource(ids(40).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        // Refetch round: 20..39 reads before r10 and r25 swap and lands last; 0..19 reads after.
        source.delays = { start, read ->
            when {
                read != 1 -> 0L to 0L
                start == 0 -> 100L to 0L
                else -> 0L to 200L
            }
        }
        source.beforeSnapshot = { start, read ->
            if (start == 0 && read == 1) {
                source.list = source.list.mapIndexed { i, row ->
                    when (i) {
                        10 -> source.list[25]
                        25 -> source.list[10]
                        else -> row
                    }
                }
            }
        }
        return source
    }

    @Test
    fun refetchWritingNothing_doesNotConfirm() = runTest {
        val source = ListSource(ids(40).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        source.silent = { _, read -> read == 1 }
        val c = collect(pager(source))
        loadBothPages(c)

        val data = c.latest.data
        listOf(19, 20).count { it in data.values } shouldBe 1
        data.values.values.count { it.first == "u" } shouldBe 1
        source.totalReads shouldBe 4
        c.job.cancel()
    }

    @Test
    fun holeTheRefetchCannotFill_readingItDoesNotReload() = runTest {
        val source = ListSource(ids(100).mapIndexed { i, row -> if (i == 25 || i == 30) "u" to i else row })
        // The refetch of 20..39 no longer has position 25, so the round cannot resolve "u".
        source.omitted = { start, read -> if (start == 20 && read > 0) setOf(25) else emptySet() }
        val c = collect(pager(source))
        advanceUntilIdle()
        c.latest.data[60]
        advanceUntilIdle()
        (25 in c.latest.data.values) shouldBe false
        val readsBefore = source.totalReads

        // A still viewport at 60, whose window holds the hole, keeps reading it.
        repeat(3) {
            c.latest.data[25]
            c.latest.data[60]
            advanceUntilIdle()
        }

        source.totalReads shouldBe readsBefore
        c.job.cancel()
    }

    @Test
    fun passWithNothingToFetch_stillResolvesConflictLeftBySupersededPass() = runTest {
        val source = ListSource(ids(80))
        val c = collect(pager(source))
        advanceUntilIdle()
        c.latest.data[70]
        advanceUntilIdle()
        // 0..19 and 40..79 are cached. r5 moves to 25, so loading 20..39 hides the stale 5.
        source.list = source.list.mapIndexed { i, row ->
            when (i) {
                5 -> source.list[25]
                25 -> source.list[5]
                else -> row
            }
        }
        // The conflict round of that pass is slow and gets superseded.
        source.delays = { start, read -> if (read == 1 && (start == 0 || start == 20)) 0L to 200L else 0L to 0L }

        c.latest.data[30]
        advanceTimeBy(50)
        (5 in c.latest.data.values) shouldBe false
        // Window 40..79 is fully cached: this pass has no chunks of its own.
        c.latest.data[70]
        advanceTimeBy(100)

        val data = c.latest.data
        data.values[5].shouldNotBeNull().first shouldBe "r25"
        data.values[25].shouldNotBeNull().first shouldBe "r5"
        data.keyAt(5) shouldBe "r25"
        c.snapshots.forEach(::assertUnique)
        advanceUntilIdle()
        c.job.cancel()
    }

    @Test
    fun supersededDuringRefetchRound_staleRoundWritesNothing() = runTest {
        val source = ListSource(ids(60).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        // The first conflict round snapshots the list and emits late.
        source.delays = { start, read -> if (read == 1 && (start == 0 || start == 20)) 0L to 200L else 0L to 0L }
        val c = collect(pager(source))
        advanceUntilIdle()
        c.latest.data[1]
        advanceTimeBy(50)

        // "u" leaves 20 before the stale round would land, and a new pass supersedes it.
        source.list = source.list.mapIndexed { i, row -> if (i == 20) "v" to i else row }
        c.latest.data[50]
        advanceUntilIdle()

        val data = c.latest.data
        data.values[19].shouldNotBeNull().first shouldBe "u"
        data.values[20].shouldNotBeNull().first shouldBe "v"
        data.keyAt(19) shouldBe "u"
        data.keyAt(20) shouldBe "v"
        c.snapshots.forEach(::assertUnique)
        c.job.cancel()
    }

    @Test
    fun conflictArisingInRefetchRound_staysHidden() = runTest {
        val source = swapDuringRefetchSource()
        val c = collect(pager(source))
        loadBothPages(c)

        val data = c.latest.data
        // The refetched real duplicate is confirmed...
        data.values[19].shouldNotBeNull().first shouldBe "u"
        data.values[20].shouldNotBeNull().first shouldBe "u"
        // ...while r25, duplicated by the round itself, stays on one position only.
        data.values.values.count { it.first == "r25" } shouldBe 1
        listOf(10, 25).count { it in data.values } shouldBe 1
        c.snapshots.forEach(::assertKeysUnique)
        // No second round.
        source.totalReads shouldBe 4
        c.job.cancel()
    }

    @Test
    fun readingHiddenPosition_reloadsItsPage() = runTest {
        val source = swapDuringRefetchSource()
        val c = collect(pager(source))
        loadBothPages(c)
        val hole = listOf(10, 25).single { it !in c.latest.data.values }
        val holePage = hole - hole % 20
        val readsBefore = source.reads.getValue(holePage)

        // A still viewport: the hole is read, then the row last requested.
        c.latest.data[hole]
        c.latest.data[1]
        advanceUntilIdle()

        (source.reads.getValue(holePage) > readsBefore) shouldBe true
        val data = c.latest.data
        data.values[hole].shouldNotBeNull().first shouldBe source.list[hole].first
        data.keyAt(hole) shouldBe source.list[hole].first
        c.snapshots.forEach(::assertKeysUnique)
        c.job.cancel()
    }

    @Test
    fun refresh_clearsKeys() = runTest {
        val source = ListSource(ids(40).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        val pager = pager(source)
        val c = collect(pager)
        loadBothPages(c)
        c.latest.data.keyAt(20) shouldBe "u"

        // 0..19 lands before 20..39, on reload and on refetch alike.
        source.delays = { start, _ -> 0L to if (start == 0) 10L else 20L }
        val before = c.snapshots.size
        pager.refresh()
        runCurrent()

        val cleared = c.snapshots.drop(before).first { it.data.values.isEmpty() }
        (0 until cleared.data.size).all { isFallback(cleared.data.keyAt(it)) } shouldBe true
        advanceUntilIdle()
        // The confirmation was dropped with the cache: "u" is hidden again until the round confirms it.
        c.snapshots.drop(before).any { 20 in it.data.values && 19 !in it.data.values } shouldBe true
        c.latest.data.values.keys shouldContainExactlyInAnyOrder (0..39).toList()
        c.job.cancel()
    }

    @Test
    fun totalChange_resetsKeyIndex() = runTest {
        val source = ListSource(ids(60).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        val c = collect(pager(source))
        loadBothPages(c)
        c.latest.data.values[20].shouldNotBeNull().first shouldBe "u"

        // One more row; "u" now only at 2.
        source.list = ids(61, prefix = "n").mapIndexed { i, row -> if (i == 2) "u" to i else row }
        c.latest.data[30]
        advanceUntilIdle()

        val afterChange = c.latest.data
        afterChange.size shouldBe 61
        afterChange.values.keys shouldContainExactlyInAnyOrder (40..59).toList()
        isFallback(afterChange.keyAt(19)) shouldBe true
        isFallback(afterChange.keyAt(20)) shouldBe true

        afterChange[1]
        advanceUntilIdle()
        val data = c.latest.data
        data.values[2].shouldNotBeNull().first shouldBe "u"
        data.keyAt(2) shouldBe "u"
        c.snapshots.forEach(::assertKeysUnique)
        c.job.cancel()
    }

    @Test
    fun withoutItemKey_unchanged() = runTest {
        val source = ListSource(ids(40).mapIndexed { i, row -> if (i == 19 || i == 20) "u" to i else row })
        val c = collect(pager(source, itemKey = null))
        loadBothPages(c)

        val data = c.latest.data
        data.values[19].shouldNotBeNull().first shouldBe "u"
        data.values[20].shouldNotBeNull().first shouldBe "u"
        (0 until data.size).all { isFallback(data.keyAt(it)) } shouldBe true
        // No conflict refetch: each page once.
        source.totalReads shouldBe 2
        c.job.cancel()
    }
}
