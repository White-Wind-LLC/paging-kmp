package ua.wwind.paging.core

import arrow.core.toNonEmptyListOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * @param T The type of items being paged
 * @param loadSize Number of items to load in each request (default: 20)
 * @param preloadSize Number of items to preload around current position (default: 60)
 * @param cacheSize Maximum number of items to keep in memory (default: 100)
 * @param scope CoroutineScope for launching load operations
 * @param readData function to load data portions from the data source
 */
@OptIn(FlowPreview::class)
public class Pager<T>(
    private val loadSize: Int = 20,
    private val preloadSize: Int = 60,
    private val cacheSize: Int = 100,
    private val scope: CoroutineScope,
    private val readData: (pos: Int, loadSize: Int) -> Flow<DataPortion<T>>
) {
    // Reactive storage for paged data
    private val _data: MutableStateFlow<PagingMap<T>> =
        MutableStateFlow(PagingMap(0, emptyMap(), onGet = ::onGet))

    // Current loading state (Loading, Success, or Error)
    private val _loadState: MutableStateFlow<LoadState> = MutableStateFlow(LoadState.Loading)

    /**
     * Public flow that combines data and load state into PagingData
     * This is the main API for consumers to observe paging state changes
     */
    public val flow: Flow<PagingData<T>> = combine(_data, _loadState) { data, loadState ->
        PagingData(data, loadState, ::onGet)
    }

    // Mutex to ensure thread-safe access to internal state
    private val mutex = Mutex()

    private enum class Direction { Increase, Decrease }

    // Debounced trigger for the last accessed key only
    private val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(1)

    // Track the last position that was requested for optimization
    private var lastReadKey: Int = 0

    // Active loading job and its planned range; used to prevent duplicate loads and support cancellation
    private var currentLoadJob: Job? = null
    private var currentLoadingRange: IntRange? = null

    init {
        // Set up debounced loading pipeline
        scope.launch {
            keyTrigger
                .debounce(300) // Wait 300ms for rapid position changes to settle
                .distinctUntilChanged() // Ignore duplicate keys
                .collect { key ->
                    if (key <= 0) return@collect
                    val direction = if (lastReadKey > 0 && key < lastReadKey) Direction.Increase else Direction.Decrease

                    // Compute the range that would be loaded for this key based on current state
                    val plannedRange = computeFetchFullRangeForKey(key)

                    // If there is an active load and the new key is inside its range – ignore this key
                    val activeJob = currentLoadJob
                    if (activeJob?.isActive == true && currentLoadingRange?.contains(key) == true) {
                        return@collect
                    }

                    // If there is an active load but the key is outside the current loading range – cancel and start new
                    if (activeJob?.isActive == true && (currentLoadingRange?.contains(key) != true)) {
                        activeJob.cancel(CancellationException("Pager: superseded by new key $key"))
                    }

                    // Start a new loading job for the new key
                    currentLoadingRange = plannedRange
                    val job = launch {
                        loadPortion(key = key, primaryDirection = direction)
                    }
                    currentLoadJob = job
                    job.invokeOnCompletion {
                        // Clear references only if this is still the current job
                        if (currentLoadJob === job) {
                            currentLoadJob = null
                            currentLoadingRange = null
                        }
                    }
                }
        }
    }

    /**
     * Main loading algorithm that determines what data to fetch and manages cache
     *
     * Algorithm overview:
     * 1. Calculate fetch ranges around the requested position
     * 2. Identify missing data that needs to be loaded
     * 3. Load data in chunks, prioritizing closest to requested position
     * 4. Update cache, removing items outside cache range
     * 5. Handle errors gracefully with retry capability
     */
    private suspend fun loadPortion(key: Int, primaryDirection: Direction) = mutex.withLock {
        try {
            val pagingData = _data.value

            // Calculate the valid range of positions (1-based indexing)
            val fullRange = 1..pagingData.size.coerceAtLeast(1)
            val coercedKey = key.coerceIn(fullRange)

            // Current loaded data
            val currentDataMap = pagingData.values

            // Find continuous range of already loaded data
            val dataRange = findContinuousRange(currentDataMap)

            // Calculate the full range we want to have loaded around the key
            val fetchFullRange =
                if (pagingData.size > 0)
                // Normal case: preload around the requested position
                    ((coercedKey - preloadSize)..<coercedKey + preloadSize)
                        .coerceIn(fullRange)
                else
                // Initial load case: load from beginning
                    1..preloadSize

            // Update the currently loading range to reflect the actual computation at runtime
            currentLoadingRange = fetchFullRange

            // Determine the primary range to load first (centered around the requested position)
            val startFetchRange =
                ((coercedKey - loadSize / 2)..<(coercedKey - loadSize / 2 + loadSize))
                    .coerceIn(fetchFullRange)
                    .expandTo(size = loadSize, limit = fetchFullRange.last)

            // Chunks for the primary centered range first
            val prioritizedChunks: List<IntRange> = startFetchRange
                .minus(dataRange)
                .flatMap { it.chunkedRanges(loadSize) }

            // Compute ranges before and after the primary range
            val beforeRangesRaw: List<IntRange> = (fetchFullRange.first..<startFetchRange.first)
                .takeIf { startFetchRange.first > fetchFullRange.first }
                ?.minus(dataRange) ?: emptyList()
            val afterRangesRaw: List<IntRange> = ((startFetchRange.last + 1)..fetchFullRange.last)
                .takeIf { startFetchRange.last < fetchFullRange.last }
                ?.minus(dataRange) ?: emptyList()

            // Extend edge pieces to a full load when they touch fetchFullRange boundaries
            fun extendEdges(pieces: List<IntRange>): List<IntRange> = pieces.map { piece ->
                val pieceCount = piece.count()
                when {
                    piece.first == fetchFullRange.first && pieceCount < loadSize -> {
                        val start = (piece.first - (loadSize - pieceCount)).coerceAtLeast(1)
                        start..piece.last
                    }

                    piece.last == fetchFullRange.last && pieceCount < loadSize -> {
                        val end = piece.last + (loadSize - pieceCount)
                        piece.first..end
                    }

                    else -> piece
                }
            }

            val beforeChunks: List<IntRange> = extendEdges(beforeRangesRaw)
                .flatMap { it.chunkedRanges(loadSize) }
            val afterChunks: List<IntRange> = extendEdges(afterRangesRaw)
                .flatMap { it.chunkedRanges(loadSize) }

            // Directional prioritization: when moving up (new key < old), load increasing indices first; else decreasing first
            val directionalChunks: List<IntRange> = when (primaryDirection) {
                Direction.Increase -> afterChunks + beforeChunks
                Direction.Decrease -> beforeChunks + afterChunks
            }.sortedBy { abs(it.first - key) }

            // Build ordered list of ranges to load
            val enqueue = prioritizedChunks + directionalChunks

            // Apply cache size limit (immutable). We must avoid mutating the same Map instance
            // across emissions, otherwise StateFlow's equality check can suppress updates.
            // Do not constrain cacheRange by the (possibly unknown) fullRange; total size may be 0 initially
            // and will be corrected by remote portions. We keep absolute window around the key.
            val cacheRange = (coercedKey - cacheSize)..<(coercedKey + cacheSize)
            var dataMap: Map<Int, T> = currentDataMap.filterKeys { it in cacheRange }

            // Execute loading operations
            enqueue
                .toNonEmptyListOrNull() // Only proceed if there's something to load
                ?.also {
                    _loadState.value = LoadState.Loading // Signal loading started
                }?.onEach { fetchRange ->
                    // Load each range
                    val loadSize = fetchRange.last - fetchRange.first + 1
                    readData(fetchRange.first, loadSize)
                        .collect { portion ->
                            // Build a new immutable map snapshot for each emission to ensure StateFlow emits
                            val updatedValues: Map<Int, T> = (dataMap + portion.values)
                                .filterKeys { it in cacheRange }
                            dataMap = updatedValues

                            _data.update {
                                PagingMap(
                                    size = portion.totalSize,
                                    values = updatedValues,
                                    onGet = ::onGet
                                )
                            }
                        }

                }?.also {
                    _loadState.value = LoadState.Success // Signal loading completed
                }

            lastReadKey = key
        } catch (e: CancellationException) {
            // Preserve cancellation for proper coroutine cleanup
            throw e
        } catch (e: Exception) {
            // Convert other exceptions to LoadState.Error for retry handling
            _loadState.value = LoadState.Error(e, key)
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

    /**
     * Called when UI accesses a specific position
     * Triggers debounced loading for that position
     *
     * @param key The position being accessed (1-based)
     */
    private fun onGet(key: Int) {
        // Only the last key matters; debounce will stabilize and compute direction
        keyTrigger.update { key }
    }

    private fun computeFetchFullRangeForKey(key: Int): IntRange {
        val pagingData = _data.value
        val fullRange = 1..pagingData.size.coerceAtLeast(1)
        val coercedKey = key.coerceIn(fullRange)

        return if (pagingData.size > 0)
            ((coercedKey - preloadSize)..<coercedKey + preloadSize)
                .coerceIn(fullRange)
        else
            1..preloadSize
    }
}

// Extension functions for range manipulation

/**
 * Subtracts one range from another, returning remaining ranges
 * Used to calculate what data still needs to be loaded
 */
private operator fun IntRange.minus(range: IntRange?): List<IntRange> {
    return when {
        range == null -> listOf(this)
        first < range.first && last in range -> listOf(first..<range.first)
        first in range && last > range.last -> listOf(range.last + 1..last)
        first in range && last in range -> emptyList()
        range.first in this && range.last in this -> listOf(
            first..<range.first,
            range.last + 1..last
        )

        else -> listOf(this)
    }
}

/**
 * Checks if this range completely contains another range
 */
private operator fun IntRange.contains(range: IntRange): Boolean {
    return range.first in this && range.last in this
}

/**
 * Coerces this range to fit within another range
 */
private fun IntRange.coerceIn(range: IntRange): IntRange {
    return first.coerceIn(range)..last.coerceIn(range)
}

/**
 * Splits a range into smaller chunks of specified size
 * Used to break large loading ranges into manageable requests
 */
private fun IntRange.chunkedRanges(size: Int): List<IntRange> {
    return (first..last step size)
        .map { start -> start..(start + size - 1).coerceAtMost(last) }
}

/**
 * Expands a range to reach the specified size, up to the limit
 * Used to ensure minimum load sizes for efficiency
 */
private fun IntRange.expandTo(size: Int, limit: Int): IntRange {
    return when {
        last - first + 1 >= size -> return this
        else -> first..<(first + size).coerceAtMost(limit)
    }
}
