package ua.wwind.paging.core

import arrow.core.toNonEmptyListOrNull
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Main paging controller that manages data loading, caching, and state management.
 *
 * The Pager implements intelligent preloading and memory management:
 * - Data is loaded in chunks around the requested position
 * - Chunk boundaries are snapped to a [loadSize] grid anchored at 0, so the same absolute positions
 *   are always requested under the same `(offset, limit)` pair and never under overlapping ones
 * - Cache is automatically managed to prevent memory leaks
 * - All operations are debounced to prevent excessive API calls
 * - Thread-safe using Mutex for concurrent access
 *
 * [preloadSize] and [cacheSize] are both radii in indices around the last accessed position, and
 * `cacheSize >= preloadSize` is required: a cache narrower than the preload window would discard
 * most of every portion the moment it arrives.
 *
 * @param T The type of items being paged
 * @param loadSize Number of items to load in each request (default: 20)
 * @param preloadSize Preload radius in indices around the current position, in both directions
 * (default: 60)
 * @param cacheSize Cache radius in indices around the current position; items outside are evicted,
 * so the cache holds up to `2 * cacheSize` items (default: 100). Must be `>= preloadSize`.
 * @param keyDebounceMs Debounce delay applied to position changes before a load is scheduled
 * (default: 300). The very first load bypasses it - there is nothing to debounce yet - as do
 * [refresh] and `PagingData.retry`.
 * @param readData function to load data portions from the data source
 */
@OptIn(FlowPreview::class)
public class Pager<T>(
    private val loadSize: Int = 20,
    private val preloadSize: Int = 60,
    private val cacheSize: Int = 100,
    private val keyDebounceMs: Long = 300,
    private val readData: (pos: Int, loadSize: Int) -> Flow<DataPortion<T>>,
) {
    init {
        require(loadSize > 0) { "loadSize must be > 0" }
        require(preloadSize >= 0) { "preloadSize must be >= 0" }
        require(cacheSize >= preloadSize) {
            cacheRadiusTooSmallMessage(cacheSize, preloadName = "preloadSize", preloadSize = preloadSize)
        }
        require(keyDebounceMs >= 0) { "keyDebounceMs must be >= 0" }
    }

    // External refresh trigger (fan-out to active collections)
    private val refreshRequests: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 64)

    private enum class Direction { Increase, Decrease }

    /** What made the pipeline schedule a load; decides how the in-flight load is treated. */
    private enum class Reason { Access, Retry, Refresh }

    private data class LoadRequest(val key: Int, val reason: Reason)

    /**
     * Public flow that combines data and load state into PagingData, with internal lifecycle-bound jobs.
     */
    public val flow: Flow<PagingData<T>> = channelFlow {
        // Debounced trigger for the last accessed key only
        val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(0)

        // Retries travel on their own channel: keyTrigger already holds the key that failed, and a
        // StateFlow conflates equal values, so routing a retry through it would emit nothing.
        val retryRequests: MutableSharedFlow<Int> = MutableSharedFlow(extraBufferCapacity = 64)

        // Entry access
        fun onGet(key: Int) {
            keyTrigger.update { key }
        }

        // PagingData.retry: keep the tracked position in sync, then force a load for it
        fun onRetry(key: Int) {
            keyTrigger.update { key }
            retryRequests.tryEmit(key)
        }

        // Reactive storage for paged data
        val data: MutableStateFlow<PagingMap<T>> =
            MutableStateFlow(PagingMap(0, persistentMapOf(), onGet = ::onGet))

        // Current loading state (Loading, Success, or Error)
        val loadState: MutableStateFlow<LoadState> = MutableStateFlow(LoadState.Success)

        // Mutex to ensure thread-safe access to internal state
        val mutex = Mutex()

        // Track the last position that was requested for optimization
        var lastReadKey: Int = -1

        // Active loading job and its planned range; used to prevent duplicate loads and support cancellation
        var currentLoadJob: Job? = null
        var currentLoadingRange: IntRange? = null

        // Combine and emit data
        val emitter = launch {
            combine(data, loadState.onStart { emit(LoadState.Success) }) { data, loadState ->
                PagingData(data, loadState, ::onRetry)
            }.collect { paging -> send(paging) }
        }

        // Position changes are debounced, except for the very first one: at that point there is no
        // scroll to settle, so waiting only delays the first paint. `onEach` runs in the debounce
        // operator's own coroutine, so the flag is always set before the next timeout is computed.
        var debounceKeys = false
        val accessRequests: Flow<LoadRequest> = keyTrigger
            .debounce { if (debounceKeys) keyDebounceMs else 0L }
            .onEach { debounceKeys = true }
            .distinctUntilChanged()
            .map { key -> LoadRequest(key, Reason.Access) }

        // One collector drives every load, so the scheduling state above is touched from a single
        // coroutine and a refresh can never interleave with the key pipeline.
        val keysJob = launch {
            merge(
                accessRequests,
                retryRequests.map { key -> LoadRequest(key, Reason.Retry) },
                refreshRequests.map { LoadRequest(keyTrigger.value, Reason.Refresh) },
            ).collect { request ->
                val key = request.key
                if (key < 0) return@collect

                when (request.reason) {
                    Reason.Refresh -> {
                        // Await the cancellation: the in-flight load merges from a pre-refresh
                        // cache snapshot and would otherwise write the cleared entries back.
                        currentLoadJob?.cancelAndJoin()
                        currentLoadJob = null
                        currentLoadingRange = null
                        data.update { it.copy(values = persistentMapOf()) }
                        lastReadKey = -1
                    }

                    Reason.Retry -> {
                        currentLoadJob?.cancel(CancellationException("Pager: superseded by retry for key $key"))
                        currentLoadJob = null
                        currentLoadingRange = null
                    }

                    Reason.Access -> {
                        val activeJob = currentLoadJob
                        if (activeJob?.isActive == true) {
                            if (currentLoadingRange?.contains(key) == true) return@collect
                            activeJob.cancel(CancellationException("Pager: superseded by new key $key"))
                        }
                    }
                }

                val direction =
                    if (lastReadKey >= 0 && key < lastReadKey) Direction.Increase else Direction.Decrease

                currentLoadingRange = computeFetchFullRangeForKey(data.value, key)
                val job = launch {
                    loadPortion(
                        dataFlow = data,
                        loadStateFlow = loadState,
                        mutex = mutex,
                        key = key,
                        primaryDirection = direction,
                        onGet = ::onGet,
                    )
                }
                currentLoadJob = job
                job.invokeOnCompletion {
                    if (currentLoadJob === job) {
                        currentLoadJob = null
                        currentLoadingRange = null
                    }
                }

                lastReadKey = key
            }
        }

        awaitClose {
            emitter.cancel()
            keysJob.cancel()
            currentLoadJob?.cancel()
        }
    }

    /**
     * Drops every cached item and reloads the window around the last accessed position.
     *
     * The in-flight load, if any, is cancelled first so that it cannot merge its pre-refresh
     * snapshot back over the cleared cache.
     */
    public fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    /**
     * Main loading algorithm that determines what data to fetch and manages cache
     *
     * Algorithm overview:
     * 1. [planLoad] calculates which ranges are missing around the requested position and in
     *    which order they should be fetched
     * 2. [runLoads] fetches them, updating the cache and the load state as portions arrive
     * 3. Errors are converted into [LoadState.Error] so the caller can retry
     */
    private suspend fun loadPortion(
        dataFlow: MutableStateFlow<PagingMap<T>>,
        loadStateFlow: MutableStateFlow<LoadState>,
        mutex: Mutex,
        key: Int,
        primaryDirection: Direction,
        onGet: (Int) -> Unit,
    ) = mutex.withLock {
        try {
            val pagingData = dataFlow.value
            val plan = planLoad(pagingData, key, primaryDirection)
            runLoads(
                plan = plan,
                cachedValues = pagingData.values,
                dataFlow = dataFlow,
                loadStateFlow = loadStateFlow,
                onGet = onGet,
            )
        } catch (e: CancellationException) {
            // Preserve cancellation for proper coroutine cleanup
            throw e
        } catch (e: Exception) {
            // Convert other exceptions to LoadState.Error for retry handling
            loadStateFlow.value = LoadState.Error(e, key)
        }
    }

    /**
     * Works out what still has to be fetched around [key] and in which order.
     *
     * The chunk holding [key] comes first, the rest follows [primaryDirection]; already loaded
     * positions are skipped. Every chunk is a cell of the [loadSize] grid anchored at 0, so a
     * position is never requested under two different ranges.
     */
    private fun planLoad(pagingData: PagingMap<T>, key: Int, primaryDirection: Direction): LoadPlan {
        // Calculate the valid range of positions
        val fullRange = 0..<pagingData.size.coerceAtLeast(1)
        val coercedKey = key.coerceIn(fullRange)

        // Find continuous range of already loaded data
        val dataRange = findContinuousRange(pagingData.values)

        // The full range we want to have loaded around the key
        val fetchFullRange = computeFetchFullRangeForKey(pagingData, key)

        // The planned range is tracked at scheduling time; no need to update here
        // The grid cell holding the requested position is fetched first
        val startFetchRange = gridCellAt(coercedKey, limit = fetchFullRange.last)

        // Chunks for the primary grid cell first
        val prioritizedChunks: List<IntRange> = startFetchRange
            .minus(dataRange)
            .flatMap { it.gridChunks(limit = fetchFullRange.last) }

        val directionalChunks = computeDirectionalChunks(
            fetchFullRange = fetchFullRange,
            startFetchRange = startFetchRange,
            dataRange = dataRange,
            key = key,
            primaryDirection = primaryDirection,
        )

        // Do not constrain cacheRange by the (possibly unknown) fullRange; total size may be 0 initially
        // and will be corrected by remote portions. We keep absolute window around the key. The window
        // this very load fetches is always kept: grid alignment can push it slightly past the radius,
        // and pruning what has just been requested would only cause an immediate refetch.
        val cacheStart = minOf(coercedKey - cacheSize, fetchFullRange.first)
        val cacheEnd = maxOf(coercedKey + cacheSize - 1, fetchFullRange.last)

        return LoadPlan(
            chunks = (prioritizedChunks + directionalChunks).distinct(),
            cacheRange = cacheStart..cacheEnd,
        )
    }

    /**
     * Orders the ranges surrounding the primary one: when moving up (new key < old), increasing
     * indices are loaded first, otherwise decreasing ones.
     */
    private fun computeDirectionalChunks(
        fetchFullRange: IntRange,
        startFetchRange: IntRange,
        dataRange: IntRange?,
        key: Int,
        primaryDirection: Direction,
    ): List<IntRange> {
        // Compute ranges before and after the primary range
        val beforeRangesRaw: List<IntRange> = (fetchFullRange.first..<startFetchRange.first)
            .takeIf { startFetchRange.first > fetchFullRange.first }
            ?.minus(dataRange) ?: emptyList()
        val afterRangesRaw: List<IntRange> = ((startFetchRange.last + 1)..fetchFullRange.last)
            .takeIf { startFetchRange.last < fetchFullRange.last }
            ?.minus(dataRange) ?: emptyList()

        // No edge extension is needed: grid cells are always a full loadSize, apart from the last one
        // of the data set.
        val beforeChunks: List<IntRange> = beforeRangesRaw
            .flatMap { it.gridChunks(limit = fetchFullRange.last) }
        val afterChunks: List<IntRange> = afterRangesRaw
            .flatMap { it.gridChunks(limit = fetchFullRange.last) }

        return when (primaryDirection) {
            Direction.Increase -> afterChunks + beforeChunks
            Direction.Decrease -> beforeChunks + afterChunks
        }.sortedBy { abs(it.first - key) }
    }

    /**
     * Fetches every range of [plan] in order, publishing each portion as it arrives.
     *
     * [cachedValues] is the cache snapshot the merges start from; it is pruned to the plan's cache
     * window before the first fetch.
     */
    private suspend fun runLoads(
        plan: LoadPlan,
        cachedValues: PersistentMap<Int, T>,
        dataFlow: MutableStateFlow<PagingMap<T>>,
        loadStateFlow: MutableStateFlow<LoadState>,
        onGet: (Int) -> Unit,
    ) {
        // Apply cache size limit (immutable). We must avoid mutating the same Map instance
        // across emissions, otherwise StateFlow's equality check can suppress updates.
        val cache = CacheAccumulator(cachedValues.pruneToRange(plan.cacheRange), plan.cacheRange)

        plan.chunks
            .toNonEmptyListOrNull() // Only proceed if there's something to load
            ?.also {
                loadStateFlow.value = LoadState.Loading // Signal loading started
            }?.onEach { fetchRange ->
                // Load each range
                val fetchSize = fetchRange.last - fetchRange.first + 1
                readData(fetchRange.first, fetchSize)
                    .collect { portion ->
                        // Build a new immutable map snapshot for each emission to ensure StateFlow emits
                        dataFlow.update { currentData -> cache.next(currentData, portion, onGet) }
                    }
            }?.also {
                loadStateFlow.value = LoadState.Success // Signal loading completed
            }
    }

    /**
     * Finds the largest continuous range of loaded data
     * This helps optimize loading by avoiding gaps in already loaded data
     *
     * Algorithm:
     * 1. Find center of loaded data
     * 2. Expand backwards while consecutive keys exist
     * 3. Expand forwards while consecutive keys exist
     *
     * @param map Current loaded data map
     * @return Continuous range or null if no data loaded
     */
    private fun findContinuousRange(map: Map<Int, T>): IntRange? {
        val centerKey = map.keys.average().toInt()
        if (centerKey !in map) return null

        var startKey = centerKey
        while (startKey - 1 in map) {
            startKey--
        }

        var endKey = centerKey
        while (endKey + 1 in map) {
            endKey++
        }

        return startKey..endKey
    }

    private fun computeFetchFullRangeForKey(pagingData: PagingMap<T>, key: Int): IntRange {
        val fullRange = 0..<pagingData.size.coerceAtLeast(1)
        val coercedKey = key.coerceIn(fullRange)

        return if (pagingData.size > 0) {
            // Snap the window outwards to whole grid cells so that every chunk cut out of it starts
            // on a multiple of loadSize.
            val start = alignDown((coercedKey - preloadSize).coerceAtLeast(0))
            val endExclusive = alignUp(coercedKey + preloadSize)
            (start..<endExclusive).coerceIn(fullRange)
        } else {
            0..<loadSize
        }
    }

    /** Start of the [loadSize] grid cell holding [position]; the grid is anchored at 0. */
    private fun alignDown(position: Int): Int = position - position.mod(loadSize)

    /** Smallest grid boundary at or after [position]. */
    private fun alignUp(position: Int): Int {
        val remainder = position.mod(loadSize)
        return if (remainder == 0) position else position + (loadSize - remainder)
    }

    /** The grid cell holding [position], truncated at [limit] when it is the last one of the data set. */
    private fun gridCellAt(position: Int, limit: Int): IntRange {
        val start = alignDown(position)
        return start..(start + loadSize - 1).coerceAtMost(limit)
    }

    /**
     * Splits a range into cells of the [loadSize] grid anchored at 0.
     *
     * A range that starts mid-cell is extended back to the cell boundary: requesting the whole cell
     * keeps the `(offset, limit)` pair stable across passes - which is what makes the request
     * cacheable - at the cost of re-reading the few positions before it.
     *
     * [limit] is the last index worth requesting, so the trailing cell of the data set is truncated
     * rather than reaching past the end.
     */
    private fun IntRange.gridChunks(limit: Int): List<IntRange> {
        if (first > last) return emptyList()
        return (alignDown(first)..last step loadSize).map { start -> gridCellAt(start, limit) }
    }
}

/**
 * Ranges a single [Pager.loadPortion] call has to fetch, in the order they should be requested,
 * together with the cache window the result is constrained to.
 */
private data class LoadPlan(val chunks: List<IntRange>, val cacheRange: IntRange)

/**
 * Builds the successive cache snapshots of one [Pager.loadPortion] call.
 *
 * [cacheRange] is fixed for the duration of a call, so once the values have been pruned to it every
 * later merge only has to write its own delta. A portion that changes the total size replaces the
 * map wholesale, which breaks that invariant, so the next merge prunes again.
 */
private class CacheAccumulator<T>(private var values: PersistentMap<Int, T>, private val cacheRange: IntRange) {
    private var pruned: Boolean = true

    fun next(current: PagingMap<T>, portion: DataPortion<T>, onGet: (Int) -> Unit): PagingMap<T> {
        if (current.size != portion.totalSize) {
            values = portion.values
            pruned = false
            return PagingMap(size = portion.totalSize, values = portion.values, onGet = onGet)
        }

        values = values.mergeIntoCache(
            incoming = portion.values,
            cacheRange = cacheRange,
            pruneExisting = !pruned,
        )
        pruned = true
        return PagingMap(size = portion.totalSize, values = values, onGet = onGet)
    }
}

// Extension functions for range manipulation

/**
 * Subtracts one range from another, returning remaining ranges
 * Used to calculate what data still needs to be loaded
 */
private operator fun IntRange.minus(range: IntRange?): List<IntRange> = when {
    range == null -> listOf(this)

    first < range.first && last in range -> listOf(first..<range.first)

    first in range && last > range.last -> listOf(range.last + 1..last)

    first in range && last in range -> emptyList()

    range.first in this && range.last in this -> listOf(
        first..<range.first,
        range.last + 1..last,
    )

    else -> listOf(this)
}

/**
 * Checks if this range completely contains another range
 */
private operator fun IntRange.contains(range: IntRange): Boolean = range.first in this && range.last in this

/**
 * Coerces this range to fit within another range
 */
private fun IntRange.coerceIn(range: IntRange): IntRange = first.coerceIn(range)..last.coerceIn(range)
