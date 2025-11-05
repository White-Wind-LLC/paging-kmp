package ua.wwind.paging.core.stream

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.wwind.paging.core.BuildKonfig
import ua.wwind.paging.core.ExperimentalStreamingPagerApi
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.core.PagingMap
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
 * @param readTotal Continuous flow of total item count.
 * @param readPortion Flow for a particular portion; returns only the values map (no total).
 */
@ExperimentalStreamingPagerApi
@OptIn(FlowPreview::class)
public class StreamingPager<T>(
    private val config: StreamingPagerConfig = StreamingPagerConfig(),
    private val readTotal: () -> Flow<Int>,
    private val readPortion: (pos: Int, loadSize: Int) -> Flow<Map<Int, T>>,
) {
    init {
        require(config.loadSize > 0) { "loadSize must be > 0" }
        require(config.preloadSize >= 0) { "preloadSize must be >= 0" }
        require(config.cacheSize >= 0) { "cacheSize must be >= 0" }
        require(config.closeThreshold >= 0) { "closeThreshold must be >= 0" }
        require(config.keyDebounceMs >= 0) { "keyDebounceMs must be >= 0" }
    }

    /** Public flow of paging state for the UI. Jobs are bound to the collection lifecycle. */
    public val flow: Flow<PagingData<T>> = channelFlow {

        // Local, per-collection state
        val logger = Logger(
            StaticConfig(
                minSeverity = runCatching { Severity.valueOf(BuildKonfig.LOG_LEVEL) }
                    .getOrDefault(Severity.Debug)
            )
        ).withTag("StreamingPager")

        val mutex = Mutex()
        val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(0)
        var lastReadKey: Int = 0
        var previousKey: Int = 0

        // onGet trigger from PagingMap
        fun onGet(key: Int) {
            keyTrigger.update { key }
        }

        val data: MutableStateFlow<PagingMap<T>> =
            MutableStateFlow(PagingMap(0, emptyMap(), onGet = ::onGet))

        val rangeLoadStates: MutableStateFlow<Map<IntRange, LoadState>> = MutableStateFlow(emptyMap())
        val loadStateFlow: Flow<LoadState> = rangeLoadStates
            .map { stateMap ->
                if (stateMap.values.contains(LoadState.Loading)) {
                    LoadState.Loading
                } else {
                    val firstError = stateMap.values.firstOrNull { it is LoadState.Error }
                    firstError ?: LoadState.Success
                }
            }
            .onStart { emit(LoadState.Success) }

        // Active range collectors keyed by their inclusive range
        val activeStreams: MutableMap<IntRange, Job> = LinkedHashMap()

        fun cleanupInactiveStreamsLocked() {
            val toRemove = activeStreams.filterValues { job -> !job.isActive }.keys
            toRemove.forEach { r -> activeStreams.remove(r) }
        }

        fun alignedChunkStartForKey(key: Int, baseStart: Int): Int {
            val diff = key - baseStart
            val steps = floor(diff.toDouble() / config.loadSize).toInt()
            return baseStart + steps * config.loadSize
        }

        fun alignedChunkContaining(key: Int, baseStart: Int, totalSize: Int): IntRange {
            val start = alignedChunkStartForKey(key, baseStart).coerceAtLeast(0)
            val end = (start + config.loadSize).coerceAtMost(totalSize.coerceAtLeast(1))
            return start..<end
        }

        suspend fun onPortion(values: Map<Int, T>) {
            mutex.withLock {
                val cacheRange = ((lastReadKey - config.cacheSize)..(lastReadKey + config.cacheSize))
                data.update { current ->
                    PagingMap(
                        size = current.size,
                        values = (current.values + values).filterKeys { it in cacheRange },
                        onGet = ::onGet
                    )
                }
                null
            }
        }

        suspend fun removeStreamByRange(range: IntRange) = withContext(NonCancellable) {
            mutex.withLock {
                activeStreams.remove(range)
                rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                    current.filterNot { it.key == range }
                }
                logger.d { "openStream: finished range=$range" }
            }
        }

        fun openStream(range: IntRange) {
            val fetchSize = (range.last - range.first + 1).coerceAtLeast(0)
            if (fetchSize <= 0) return

            val job = launch {
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
                    throw e
                } catch (t: Throwable) {
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

        suspend fun onTotalChanged(newTotalRaw: Int) = mutex.withLock {
            val newTotal = newTotalRaw.coerceAtLeast(0)
            val current = data.value
            if (current.size == newTotal) return@withLock
            logger.d { "totalSize changed: ${current.size} -> $newTotal" }

            val newRange = 0..<newTotal.coerceAtLeast(1)
            val prunedValues = current.values.filterKeys { it in newRange }
            data.update {
                PagingMap(
                    size = newTotal,
                    values = prunedValues,
                    onGet = ::onGet
                )
            }

            val toClose = activeStreams.keys.filter { r -> r.first > newTotal || r.last > newTotal }
            if (toClose.isNotEmpty()) logger.d { "closing due to total shrink: $toClose" }
            toClose.forEach { r ->
                withContext(NonCancellable) {
                    activeStreams.remove(r)?.cancel(CancellationException("StreamingPager: total shrank"))
                    rangeLoadStates.update { currentStates: Map<IntRange, LoadState> ->
                        currentStates.filterNot { it.key == r }
                    }
                }
            }
            if (lastReadKey > newTotal) keyTrigger.value = newTotal
        }

        suspend fun tryAdjustStreamsForKey(key: Int) = mutex.withLock {
            logger.d { "tryAdjustStreamsForKey: key=$key" }
            cleanupInactiveStreamsLocked()

            val directionForward = key > lastReadKey

            val totalSize = data.value.size
            logger.d { "adjust: key=$key last=$lastReadKey total=$totalSize forward=$directionForward" }

            val targetChunks: List<IntRange> = if (totalSize == 0) {
                listOf(0..<config.loadSize)
            } else {
                val windowForKeyAligned = computeWindowForKeyAligned(key, totalSize, config)
                val keepers = activeStreams.keys.filter { it.intersects(windowForKeyAligned) }
                val baseStart = keepers.minByOrNull { abs(it.first - key) }?.first
                    ?: alignedChunkStartForKey(key, baseStart = 0)

                val centerChunk = alignedChunkContaining(key, baseStart, totalSize)
                val window = computeWindowAroundCenter(centerChunk, totalSize, config)

                val toCloseNow = activeStreams.keys.filter { r ->
                    distanceBeyondWindow(window, r) > config.closeThreshold
                }
                if (toCloseNow.isNotEmpty()) logger.d { "closing: $toCloseNow (window=$window, threshold>${config.closeThreshold})" }
                toCloseNow.forEach { r ->
                    activeStreams.remove(r)?.cancel(CancellationException("StreamingPager: window shifted"))
                    rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                        current.filterNot { it.key == r }
                    }
                }

                val forward = mutableListOf<IntRange>().apply {
                    var start = centerChunk.first + config.loadSize
                    while (start <= window.last) {
                        val end = (start + config.loadSize - 1).coerceAtMost(totalSize - 1)
                        add(start..end)
                        start += config.loadSize
                    }
                }

                val backward = buildList {
                    var start = centerChunk.first - config.loadSize
                    while (start + config.loadSize - 1 >= window.first && start >= 0) {
                        val end = (start + config.loadSize - 1).coerceAtMost(totalSize - 1)
                        add(start..end)
                        start -= config.loadSize
                    }
                }.reversed()

                (backward + listOf(centerChunk) + forward)
            }

            val toOpen = targetChunks.filter { it !in activeStreams }
            if (toOpen.isNotEmpty()) logger.d { "opening missing ranges: $toOpen" }
            if (toOpen.isNotEmpty()) {
                rangeLoadStates.update { current: Map<IntRange, LoadState> ->
                    current + toOpen.associateWith { LoadState.Loading }
                }
            }

            val anchor = targetChunks.firstOrNull { key in it } ?: targetChunks.firstOrNull()
            val sortedToOpen = if (anchor == null) toOpen else toOpen.sortedWith(compareBy<IntRange> {
                val delta = it.first - anchor.first
                when {
                    directionForward && delta >= 0 -> delta
                    directionForward && delta < 0 -> Int.MAX_VALUE / 2 + abs(delta)
                    !directionForward && delta <= 0 -> abs(delta)
                    else -> Int.MAX_VALUE / 2 + delta
                }
            })

            sortedToOpen.forEach { range -> openStream(range) }

            previousKey = lastReadKey
            lastReadKey = key
        }

        // Emit combined paging data to the channel
        val emitter = launch {
            combine(data, loadStateFlow) { data, loadState ->
                PagingData(data, loadState, ::onGet)
            }.collect { paging ->
                send(paging)
            }
        }

        // Collect total size updates
        val totalJob = launch {
            readTotal()
                .distinctUntilChanged()
                .collect { newTotal ->
                    val emptyBefore = data.value.size == 0
                    onTotalChanged(newTotal)
                    if (emptyBefore && newTotal > 0) {
                        tryAdjustStreamsForKey(0)
                    }
                }
        }

        // React to key accesses
        val keysJob = launch {
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

        awaitClose {
            // Cancel children (emitter, totalJob, keysJob and any openStream jobs)
            emitter.cancel()
            totalJob.cancel()
            keysJob.cancel()
            activeStreams.values.forEach { it.cancel(CancellationException("StreamingPager: flow collection cancelled")) }
            activeStreams.clear()
        }
    }
}
