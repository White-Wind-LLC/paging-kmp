package ua.wwind.paging.core

import arrow.core.toNonEmptyListOrNull
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * @param readData function to load data portions from the data source
 */
@OptIn(FlowPreview::class)
public class Pager<T>(
    private val loadSize: Int = 20,
    private val preloadSize: Int = 60,
    private val cacheSize: Int = 100,
    private val readData: (pos: Int, loadSize: Int) -> Flow<DataPortion<T>>,
) {
    init {
        require(loadSize > 0) { "loadSize must be > 0" }
        require(preloadSize >= 0) { "preloadSize must be >= 0" }
        require(cacheSize >= preloadSize) {
            cacheRadiusTooSmallMessage(cacheSize, preloadName = "preloadSize", preloadSize = preloadSize)
        }
    }

    // External refresh trigger (fan-out to active collections)
    private val refreshRequests: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 64)

    private enum class Direction { Increase, Decrease }

    /**
     * Public flow that combines data and load state into PagingData, with internal lifecycle-bound jobs.
     */
    public val flow: Flow<PagingData<T>> = channelFlow {
        // Debounced trigger for the last accessed key only
        val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(0)

        // Retry and onGet entry access
        fun onGet(key: Int) {
            keyTrigger.update { key }
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
                PagingData(data, loadState, ::onGet)
            }.collect { paging -> send(paging) }
        }

        // Handle refresh requests from outside
        val refreshJob = launch {
            refreshRequests.collectLatest {
                data.update { it.copy(values = persistentMapOf()) }
            }
        }

        // Set up debounced loading pipeline bound to this collection
        val keysJob = launch {
            keyTrigger
                .debounce(300)
                .distinctUntilChanged()
                .collect { key ->
                    if (key < 0) return@collect
                    val direction =
                        if (lastReadKey >= 0 && key < lastReadKey) Direction.Increase else Direction.Decrease

                    val plannedRange = computeFetchFullRangeForKey(data.value, key)

                    val activeJob = currentLoadJob
                    if (activeJob?.isActive == true && currentLoadingRange?.contains(key) == true) {
                        return@collect
                    }

                    if (activeJob?.isActive == true && (currentLoadingRange?.contains(key) != true)) {
                        activeJob.cancel(CancellationException("Pager: superseded by new key $key"))
                    }

                    currentLoadingRange = plannedRange
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
            refreshJob.cancel()
            keysJob.cancel()
            currentLoadJob?.cancel()
        }
    }

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
     * The range closest to [key] comes first, the rest follows [primaryDirection]; already loaded
     * positions are skipped.
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
        // Determine the primary range to load first (centered around the requested position)
        val startFetchRange =
            ((coercedKey - loadSize / 2)..<(coercedKey - loadSize / 2 + loadSize))
                .coerceIn(fetchFullRange)
                .expandTo(size = loadSize, limit = fetchFullRange.last)

        // Chunks for the primary centered range first
        val prioritizedChunks: List<IntRange> = startFetchRange
            .minus(dataRange)
            .flatMap { it.chunkedRanges(loadSize) }

        val directionalChunks = computeDirectionalChunks(
            fetchFullRange = fetchFullRange,
            startFetchRange = startFetchRange,
            dataRange = dataRange,
            key = key,
            primaryDirection = primaryDirection,
        )

        // Do not constrain cacheRange by the (possibly unknown) fullRange; total size may be 0 initially
        // and will be corrected by remote portions. We keep absolute window around the key.
        return LoadPlan(
            chunks = prioritizedChunks + directionalChunks,
            cacheRange = (coercedKey - cacheSize)..<(coercedKey + cacheSize),
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

        val beforeChunks: List<IntRange> = extendEdges(beforeRangesRaw, fetchFullRange, loadSize)
            .flatMap { it.chunkedRanges(loadSize) }
        val afterChunks: List<IntRange> = extendEdges(afterRangesRaw, fetchFullRange, loadSize)
            .flatMap { it.chunkedRanges(loadSize) }

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
            ((coercedKey - preloadSize)..<coercedKey + preloadSize)
                .coerceIn(fullRange)
        } else {
            0..<loadSize
        }
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

/**
 * Extends the pieces that touch a boundary of [bounds] to a full [loadSize], so that an edge of the
 * fetch window is never requested as a tiny leftover chunk.
 */
private fun extendEdges(pieces: List<IntRange>, bounds: IntRange, loadSize: Int): List<IntRange> = pieces.map { piece ->
    val pieceCount = piece.count()
    when {
        piece.first == bounds.first && pieceCount < loadSize -> {
            val start = (piece.first - (loadSize - pieceCount)).coerceAtLeast(0)
            start..piece.last
        }

        piece.last == bounds.last && pieceCount < loadSize -> {
            val end = piece.last + (loadSize - pieceCount)
            piece.first..end
        }

        else -> piece
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

/**
 * Splits a range into smaller chunks of specified size
 * Used to break large loading ranges into manageable requests
 */
private fun IntRange.chunkedRanges(size: Int): List<IntRange> = (first..last step size)
    .map { start -> start..(start + size - 1).coerceAtMost(last) }

/**
 * Expands a range to reach the specified size, up to the limit
 * Used to ensure minimum load sizes for efficiency
 */
private fun IntRange.expandTo(size: Int, limit: Int): IntRange {
    return when {
        last - first + 1 >= size -> return this
        else -> first..(first + size - 1).coerceAtMost(limit)
    }
}
