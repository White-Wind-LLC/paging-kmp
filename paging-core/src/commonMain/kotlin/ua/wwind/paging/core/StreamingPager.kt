package ua.wwind.paging.core

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

public data class StreamingPagerConfig(
    val loadSize: Int = 20,
    val preloadSize: Int = 60,
    val cacheSize: Int = 100,
    val closeThreshold: Int = loadSize,
    val keyDebounceMs: Long = 300,
)

/**
 * Streaming pager that manages multiple concurrent range flows with a dedicated total-size stream.
 *
 * Behavior:
 * - A continuous `readTotal()` stream emits global item count updates and drives `PagingMap.size`.
 * - Portion streams `readPortion(start, size)` emit only data maps (no totals) which are merged into a bounded cache window.
 * - The active window is computed around the last accessed key; chunk-aligned flows of size [loadSize] are opened/kept as needed.
 * - Flows are closed only when the active window moves farther than [closeThreshold] from a flow's bounds.
 * - When the total size shrinks, out-of-bounds flows are cancelled and in-memory data is pruned to the valid range.
 * - Exposes a single `Flow<PagingData<T>>` with a global `LoadState` aggregated from per-range states (priority: Loading > Error > Success).
 *
 * @param loadSize Maximum number of items per opened portion flow.
 * @param preloadSize Number of items to preload in both directions from the last accessed key.
 * @param cacheSize Maximum number of in-memory items around the last accessed key (cache window).
 * @param closeThreshold Distance beyond the active window after which a flow is closed (default = [loadSize]).
 * @param scope Coroutine scope used to launch internal collectors.
 * @param readTotal Continuous flow of total item count.
 * @param readPortion Flow for a particular portion; returns only the values map (no total).
 */
@ExperimentalStreamingPagerApi
@OptIn(FlowPreview::class)
public class StreamingPager<T>(
    private val config: StreamingPagerConfig = StreamingPagerConfig(),
    private val scope: CoroutineScope,
    private val readTotal: () -> Flow<Int>,
    private val readPortion: (pos: Int, loadSize: Int) -> Flow<Map<Int, T>>,
) {
    private val _data: MutableStateFlow<PagingMap<T>> =
        MutableStateFlow(PagingMap(0, emptyMap(), onGet = ::onGet))

    // Per-range load states for currently active streams
    private val rangeLoadStates: MutableStateFlow<Map<IntRange, LoadState>> = MutableStateFlow(emptyMap())
    public val loadState: StateFlow<LoadState> =
        rangeLoadStates
            .map { stateMap ->
                if (stateMap.values.contains(LoadState.Loading)) {
                    LoadState.Loading
                } else {
                    val firstError = stateMap.values.firstOrNull { it is LoadState.Error }
                    firstError ?: LoadState.Success
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = LoadState.Success
            )

    /** Public flow of paging state for the UI. */
    public val flow: Flow<PagingData<T>> = combine(_data, loadState) { data, loadState ->
        PagingData(data, loadState, ::onGet)
    }

    private val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(0)
    private var lastReadKey: Int = 0
    private var previousKey: Int = 0

    // Active range collectors keyed by their inclusive range
    private val activeStreams: MutableMap<IntRange, Job> = LinkedHashMap()

    // Protects activeStreams and data updates
    private val mutex = Mutex()

    private val logger = Logger(
        StaticConfig(
            minSeverity = runCatching { Severity.valueOf(BuildKonfig.LOG_LEVEL) }
                .getOrDefault(Severity.Debug)
        )).withTag("StreamingPager")

    init {
        require(config.loadSize > 0) { "loadSize must be > 0" }
        require(config.preloadSize >= 0) { "preloadSize must be >= 0" }
        require(config.cacheSize >= 0) { "cacheSize must be >= 0" }
        require(config.closeThreshold >= 0) { "closeThreshold must be >= 0" }
        require(config.keyDebounceMs >= 0) { "keyDebounceMs must be >= 0" }

        // Global collector for total size updates
        scope.launch {
            readTotal()
                .distinctUntilChanged()
                .collect { newTotal ->
                    val emptyBefore = _data.value.size == 0
                    onTotalChanged(newTotal)
                    if (emptyBefore && newTotal > 0) {
                        tryAdjustStreamsForKey(0)
                    }
                }
        }

        scope.launch {
            keyTrigger
                .debounce(config.keyDebounceMs)
                .distinctUntilChanged()
                .collect { key ->
                    logger.d { "keyTrigger: key=$key" }
                    if (key < 0) return@collect
                    tryAdjustStreamsForKey(key)
                    logger.d { "active flows: ${activeStreams.size} for ranges: ${activeStreams.keys.sortedBy { it.first }}" }
                }
        }
    }

    /** External trigger from UI access. */
    private fun onGet(key: Int) {
        keyTrigger.update { key }
    }

    private suspend fun tryAdjustStreamsForKey(key: Int) = mutex.withLock {
        // 1) Drop inactive streams
        logger.d { "tryAdjustStreamsForKey: key=$key" }
        cleanupInactiveStreamsLocked()

        // 2) Determine read direction
        val directionForward = key > lastReadKey // Example: true (previous key < 120)

        val totalSize = _data.value.size // Example: >= 172 (dataset big enough)
        logger.d { "adjust: key=$key last=$lastReadKey total=$totalSize forward=$directionForward" }

        // 3) Compute window and target chunks adapting to already opened alignment
        val targetChunks: List<IntRange> = if (totalSize == 0) {
            listOf(0..<config.loadSize)
        } else {
            val windowForKeyAligned = computeWindowForKeyAligned(key, totalSize) // Example: 100..140
            val keepers =
                activeStreams.keys.filter { it.intersects(windowForKeyAligned) } // Example: [93..112, 113..132]
            val baseStart = keepers.minByOrNull { abs(it.first - key) }?.first
                ?: alignedChunkStartForKey(key, baseStart = 0) // Example: 113

            val centerChunk = alignedChunkContaining(key, baseStart, totalSize) // Example: 113..132
            val window = computeWindowAroundCenter(centerChunk, totalSize) // Example: 93..152

            // Close streams only when the window moved farther than closeThreshold from a stream bounds
            val toCloseNow = activeStreams.keys.filter { r -> distanceBeyondWindow(window, r) > config.closeThreshold }
            if (toCloseNow.isNotEmpty()) logger.d { "closing: $toCloseNow (window=$window, threshold>${config.closeThreshold})" }
            toCloseNow.forEach { r ->
                activeStreams.remove(r)?.cancel(CancellationException("StreamingPager: window shifted"))
                // Remove range state once the range is no longer tracked
                rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                    current.filterNot { it.key == r }
                }
            }

            // Build target chunks by expanding from center and respecting alignment
            val forward = mutableListOf<IntRange>().apply { // Example result: [133..152]
                var start = centerChunk.first + config.loadSize
                while (start <= window.last) {
                    val end = (start + config.loadSize - 1).coerceAtMost(totalSize - 1)
                    add(start..end)
                    start += config.loadSize
                }
            }

            val backward = buildList { // Example result: [93..112]
                var start = centerChunk.first - config.loadSize
                while (start + config.loadSize - 1 >= window.first && start >= 0) {
                    val end = (start + config.loadSize - 1).coerceAtMost(totalSize - 1)
                    add(start..end)
                    start -= config.loadSize
                }
                // maintain ascending order
            }.reversed()

            (backward + listOf(centerChunk) + forward) // Example targetChunks: [93..112, 113..132, 133..152]
        }

        // 4) Open missing chunks, prioritize by direction
        val toOpen = targetChunks.filter { it !in activeStreams } // Example: [133..152]
        if (toOpen.isNotEmpty()) logger.d { "opening missing ranges: $toOpen" }
        // Mark each new range as Loading (stream opened, no emissions yet)
        if (toOpen.isNotEmpty()) {
            rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                current + toOpen.associateWith { LoadState.Loading }
            }
        }

        val anchor = targetChunks.firstOrNull { key in it } ?: targetChunks.firstOrNull() // Example: 113..132
        val sortedToOpen = if (anchor == null) toOpen else toOpen.sortedWith(compareBy<IntRange> {
            val delta = it.first - anchor.first
            when {
                directionForward && delta >= 0 -> delta
                directionForward && delta < 0 -> Int.MAX_VALUE / 2 + abs(delta)
                !directionForward && delta <= 0 -> abs(delta)
                else -> Int.MAX_VALUE / 2 + delta
            }
        }) // Example: [133..152]

        sortedToOpen.forEach { range -> openStream(range) }

        // Update last seen key for next direction computation
        previousKey = lastReadKey
        lastReadKey = key
    }

    private fun computeWindowForKeyAligned(key: Int, totalSize: Int): IntRange {
        val full = 0..<totalSize.coerceAtLeast(1)
        val centered = key.coerceIn(full)
        val start = (centered - config.preloadSize).coerceAtLeast(full.first)
        val end = (centered + config.preloadSize).coerceAtMost(full.last)
        return start..end
    }

    private fun computeWindowAroundCenter(centerChunk: IntRange, totalSize: Int): IntRange {
        val full = 0..<totalSize.coerceAtLeast(1)
        val start = (centerChunk.first - config.preloadSize).coerceAtLeast(full.first)
        val end = (centerChunk.last + config.preloadSize).coerceAtMost(full.last)
        return start..end
    }

    private fun alignedChunkStartForKey(key: Int, baseStart: Int): Int {
        val diff = key - baseStart
        val steps = floor(diff.toDouble() / config.loadSize).toInt()
        return baseStart + steps * config.loadSize
    }

    private fun alignedChunkContaining(key: Int, baseStart: Int, totalSize: Int): IntRange {
        val start = alignedChunkStartForKey(key, baseStart).coerceAtLeast(0)
        val end = (start + config.loadSize).coerceAtMost(totalSize.coerceAtLeast(1))
        return start..<end
    }

    private fun cleanupInactiveStreamsLocked() {
        val toRemove = activeStreams.filterValues { job -> !job.isActive }.keys
        toRemove.forEach { r -> activeStreams.remove(r) }
    }

    private fun openStream(range: IntRange) {
        val fetchSize = (range.last - range.first + 1).coerceAtLeast(0)
        if (fetchSize <= 0) return

        val job = scope.launch {
            try {
                logger.d { "openStream: start collecting range=$range size=$fetchSize" }
                readPortion(range.first, fetchSize).collect { values ->
                    logger.d { "onPortion(range=$range, count=${values.size}, keys=${values.keys.minOrNull()}..${values.keys.maxOrNull()})" }
                    onPortion(values)
                    rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                        current + (range to LoadState.Success)
                    }
                }
            } catch (e: CancellationException) {
                logger.d { "openStream: cancelled range=$range" }
                // Propagate cancellation
                throw e
            } catch (t: Throwable) {
                // Set error for this range
                logger.e(t) { "openStream: error in range=$range" }
                rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                    current + (range to LoadState.Error(t, range.first))
                }
            } finally {
                removeStreamByRange(range)
            }
        }
        activeStreams[range] = job
    }

    private suspend fun removeStreamByRange(range: IntRange) =
        withContext(NonCancellable) {
            mutex.withLock {
                // Remove only if this exact range is still registered
                activeStreams.remove(range)
                // If the range is no longer active, drop its state
                rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                    current.filterNot { it.key == range }
                }
                logger.d { "openStream: finished range=$range" }
            }
        }

    private suspend fun onPortion(values: Map<Int, T>) {
        mutex.withLock {
            // Merge values within cache window
            val cacheRange = ((lastReadKey - config.cacheSize)..(lastReadKey + config.cacheSize))

            _data.update { current ->
                PagingMap(
                    size = current.size,
                    values = (current.values + values)
                        .filterKeys { it in cacheRange },
                    onGet = ::onGet
                )
            }
            null
        }
    }

    private fun IntRange.intersects(other: IntRange): Boolean {
        return this.first <= other.last && this.last >= other.first
    }

    private fun distanceBeyondWindow(window: IntRange, range: IntRange): Int {
        return when {
            window.intersects(range) -> 0
            window.last < range.first -> range.first - window.last
            range.last < window.first -> window.first - range.last
            else -> 0
        }
    }

    private suspend fun onTotalChanged(newTotalRaw: Int) = mutex.withLock {
        val newTotal = newTotalRaw.coerceAtLeast(0)
        val current = _data.value
        if (current.size == newTotal) return@withLock
        logger.d { "totalSize changed: ${current.size} -> $newTotal" }

        // Update size and prune values outside new range
        val newRange = 0..<newTotal.coerceAtLeast(1)
        val prunedValues = current.values.filterKeys { it in newRange }
        _data.update {
            PagingMap(
                size = newTotal,
                values = prunedValues,
                onGet = ::onGet
            )
        }

        // Close streams that exceed the new total size
        val toClose = activeStreams.keys.filter { r -> r.first > newTotal || r.last > newTotal }
        if (toClose.isNotEmpty()) logger.d { "closing due to total shrink: $toClose" }
        toClose.forEach { r ->
            withContext(NonCancellable) {
                activeStreams.remove(r)?.cancel(CancellationException("StreamingPager: total shrank"))
                rangeLoadStates.update { currentStates: Map<IntRange, LoadState> -> currentStates.filterNot { it.key == r } }
            }
        }
        if (lastReadKey > newTotal) keyTrigger.value = newTotal
    }
}
