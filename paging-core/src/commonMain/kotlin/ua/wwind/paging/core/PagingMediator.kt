package ua.wwind.paging.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coordinates paging between a local cache and a remote source and exposes a Flow<PagingData<T>> per query.
 *
 * Responsibilities:
 * - Serve data from local storage first when available.
 * - Fetch and merge missing ranges from the remote source.
 * - Keep total item count in sync; clear local cache and refetch when inconsistencies are detected.
 * - Support arbitrary query/filter types while preserving stable positional ordering.
 * - Remain agnostic of specific DB/network implementations via pluggable data source interfaces.
 *
 * Behavior is controlled via [PagingMediatorConfig].
 *
 * @param T item type
 * @param Q query/filter type (use Unit if not needed)
 * @property scope Coroutine scope used to run paging tasks.
 * @property local Local paging data source.
 * @property remote Remote paging data source.
 * @property config Mediator behavior configuration.
 */
public class PagingMediator<T, Q>(
    private val scope: CoroutineScope,
    private val local: LocalDataSource<T, Q>,
    private val remote: RemoteDataSource<T, Q>,
    private val config: PagingMediatorConfig<T> = PagingMediatorConfig(),
) {

    init {
        require(config.concurrency >= 1) { "Concurrency must be at least 1" }
    }

    /**
     * Creates a paging flow bound to the given [query]. Each distinct query owns its own pager instance.
     *
     * @param query Query/filter value forwarded to the remote data source.
     * @return Flow of [PagingData] that emits cached data first and then remote updates.
     */
    public fun flow(query: Q): Flow<PagingData<T>> {
        val pager = Pager(
            loadSize = config.loadSize,
            preloadSize = config.prefetchSize,
            cacheSize = config.cacheSize,
            scope = scope,
            readData = { position, size -> loadPortion(query, position, size) }
        )
        return pager.flow
    }

    /**
     * Loads the requested 1-based range [position, position + size - 1].
     *
     * Steps:
     * 1) Read from local storage, optionally either emit raw local data (see [PagingMediatorConfig.emitOutdatedRecords]),
     *    or emit the filtered local portion where stale records are removed via [PagingMediatorConfig.isRecordStale].
     * 2) Compute missing contiguous subranges and fetch them from the remote source (optionally in parallel).
     * 3) Optionally emit intermediate remote portions as they arrive (see [PagingMediatorConfig.emitIntermediateResults]).
     * 4) Emit a merged portion and a final marker which triggers cache persistence upstream.
     *
     * Total size consistency:
     * - If fetched portions disagree with each other or with a non-empty local total size, the local cache is cleared
     *   and the full requested range is refetched once to restore consistency.
     *
     * @param query Query/filter to pass to the remote source.
     * @param position 1-based start position of the requested range.
     * @param size Number of items to load.
     * @return Cold Flow emitting [DataPortion] updates for this range.
     */
    private fun loadPortion(query: Q, position: Int, size: Int): Flow<DataPortion<T>> = kotlinx.coroutines.flow.flow {
        val requestedRangeFirst = position
        val requestedRangeLast = position + size - 1
        val requestedRange = requestedRangeFirst..requestedRangeLast

        val localPortion = local.read(position, size, query)
            .let { portion ->
                if (config.emitOutdatedRecords) emit(portion)
                portion.copy(values = portion.values.filter { !config.isRecordStale(it.value) })
            }
        if (!config.emitOutdatedRecords) emit(localPortion)

        val missingRanges =
            if (config.fetchFullRangeOnMiss) listOf(requestedRange)
            else computeMissingRanges(requestedRange, localPortion.values.keys)

        if (missingRanges.isNotEmpty()) {
            // Fetch and persist each missing contiguous range
            fetchMissingRanges(missingRanges, query, localPortion, requestedRange)
                .collect { (portion, final) ->
                    if (!final) emit(portion)
                    else local.save(portion)
                }
        }
    }

    /**
     * Fetches all missing ranges for the requested window and coordinates emissions.
     *
     * - At most [PagingMediatorConfig.concurrency] fetches are executed in parallel.
     * - When [PagingMediatorConfig.emitIntermediateResults] is true, each fetched portion is emitted.
     * - A merged portion is emitted at the end (and a final marker indicating completion of this cycle).
     *
     * If total size is inconsistent, the local cache (when non-empty) is cleared and the full range is
     * refetched exactly once, after which emissions resume.
     *
     * @param missingRanges Contiguous ranges missing in local storage.
     * @param query Query/filter for the remote source.
     * @param localPortion Portion read from local for the requested range.
     * @param requestedRange Original requested range.
     * @param isRetryAfterClear Internal flag to avoid infinite retries after clearing.
     * @return Flow emitting pairs of (portion, final). When final is true, this is the last emission for the cycle.
     */
    private fun fetchMissingRanges(
        missingRanges: List<IntRange>,
        query: Q,
        localPortion: DataPortion<T>,
        requestedRange: IntRange,
        isRetryAfterClear: Boolean = false,
    ): Flow<Pair<DataPortion<T>, Boolean>> = kotlinx.coroutines.flow.flow {
        var shouldEmitMergedPortion = !config.emitIntermediateResults
        val fetchedPortions = if (config.concurrency == 1 || missingRanges.size == 1) {
            missingRanges.map { range ->
                fetchRange(range, query)
                    .also {
                        if (config.emitIntermediateResults) emit(it to false)
                    }
            }
        } else {
            shouldEmitMergedPortion = true
            val semaphore = Semaphore(config.concurrency)
            coroutineScope {
                missingRanges.map { range ->
                    async {
                        semaphore.withPermit {
                            fetchRange(range, query)
                        }
                    }
                }.awaitAll()
            }
        }

        val distinctTotals = fetchedPortions.map { it.totalSize }.distinct()
        val inconsistentTotals =
            distinctTotals.size > 1 ||
                    (localPortion.totalSize != 0 && distinctTotals.single() != localPortion.totalSize)
        if (inconsistentTotals && !isRetryAfterClear) {
            // Total size can change after loading or be different in different portions. It means that data is
            // inconsistent. We need to refetch the full range of the data.
            if (localPortion.totalSize != 0) local.clear()
            fetchMissingRanges(listOf(requestedRange), query, localPortion, requestedRange, isRetryAfterClear = true)
                .collect { emit(it) }
        } else {
            fetchedPortions
                .reduce { acc, portion ->
                    acc.copy(values = acc.values + portion.values)
                }.also {
                    if (shouldEmitMergedPortion) emit(it to false)
                    emit(it to true)
                }
        }
    }

    /**
     * Fetches a single contiguous range from the remote data source.
     *
     * @param range Inclusive 1-based range to fetch.
     * @param query Query/filter forwarded to the remote data source.
     * @return [DataPortion] containing the fetched items and a total size hint.
     */
    private suspend fun fetchRange(
        range: IntRange,
        query: Q,
    ): DataPortion<T> {
        val fetchSize = range.last - range.first + 1
        return remote.fetch(range.first, fetchSize, query)
    }
}

/**
 * Abstraction of a local cache/data store that provides positional reads and incremental persistence.
 * Implementations may be backed by SQL databases, key-value stores, in-memory caches, etc.
 *
 * Implementations must preserve stable positional ordering and use absolute 1-based positions as map keys.
 */
public interface LocalDataSource<T, Q> {
    /**
     * Reads a portion starting at [startPosition] (1-based) with [size] items.
     *
     * Implementations may return sparse results; missing positions must be omitted from the values map.
     *
     * @param startPosition 1-based start position.
     * @param size Number of items to read.
     * @return [DataPortion] with present values and a total size hint (0 when unknown).
     */
    public suspend fun read(startPosition: Int, size: Int, query: Q): DataPortion<T>

    /**
     * Persists or updates the supplied [portion] in the local cache.
     * Keys are absolute positions. Implementations should merge with existing records.
     */
    public suspend fun save(portion: DataPortion<T>)

    /**
     * Clears all cached data. Called when the server-reported total size changes
     * or when external invalidation occurs.
     */
    public suspend fun clear()
}

/**
 * Abstraction of a remote source capable of fetching ranges by absolute 1-based position for a given query.
 */
public interface RemoteDataSource<T, Q> {
    /**
     * Fetches a portion for [query] starting from [startPosition] (1-based) with [size] items.
     *
     * Implementations must:
     * - Return absolute 1-based positions as map keys.
     * - Provide a consistent totalSize for a given query at the time of the request.
     */
    public suspend fun fetch(startPosition: Int, size: Int, query: Q): DataPortion<T>
}

/**
 * Computes contiguous missing subranges inside [expected] based on the set of present absolute positions.
 *
 * Example: expected = 10..15, present = {10, 12, 15} -> missing: 11..11, 13..14
 *
 * @param expected Inclusive expected range.
 * @param presentKeys Absolute positions that are present locally.
 * @return Missing contiguous ranges in ascending order.
 */
private fun computeMissingRanges(expected: IntRange, presentKeys: Set<Int>): List<IntRange> {
    if (expected.isEmpty()) return emptyList()
    val missing = mutableListOf<IntRange>()
    var start: Int? = null
    for (key in expected.first..expected.last) {
        val exists = key in presentKeys
        if (!exists && start == null) start = key
        if ((exists || key == expected.last) && start != null) {
            val endExclusive = if (exists) key else key + 1
            val end = endExclusive - 1
            if (end >= start) missing.add(start..end)
            start = null
        }
    }
    return missing
}

/**
 * Configuration for [PagingMediator] behavior. All parameters have sensible defaults.
 *
 * - [loadSize]: Number of items the pager requests per load.
 * - [prefetchSize]: Number of items to prefetch ahead of the viewport.
 * - [cacheSize]: Maximum number of items retained by the pager window.
 * - [isRecordStale]: Predicate used to filter out stale records from local before emission.
 * - [concurrency]: Maximum number of concurrent remote fetches.
 * - [fetchFullRangeOnMiss]: If true, fetch the full requested range when any position is missing.
 * - [emitOutdatedRecords]: If true, emit raw local data before filtering stale records.
 * - [emitIntermediateResults]: If true, emit each fetched portion as it arrives.
 */
public data class PagingMediatorConfig<T>(
    val loadSize: Int = 20,
    val prefetchSize: Int = 60,
    val cacheSize: Int = 100,
    val isRecordStale: (T) -> Boolean = { false },
    val concurrency: Int = 1,
    val fetchFullRangeOnMiss: Boolean = false,
    val emitOutdatedRecords: Boolean = false,
    val emitIntermediateResults: Boolean = true,
)
