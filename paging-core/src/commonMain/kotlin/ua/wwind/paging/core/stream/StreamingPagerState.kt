package ua.wwind.paging.core.stream

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.core.PagingMap
import kotlin.math.abs

internal class StreamingPagerState<T>(
    val config: StreamingPagerConfig,
    val readPortion: (pos: Int, loadSize: Int) -> Flow<Map<Int, T>>,
    logger: Logger,
) {
    val logger: Logger = logger.withTag("StreamingPager")

    val mutex: Mutex = Mutex()
    val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(0)
    var lastReadKey: Int = 0
    var previousKey: Int = 0

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

    val activeStreams: MutableMap<IntRange, Job> = LinkedHashMap()

    fun cleanupInactiveStreamsLocked() {
        val toRemove = activeStreams.filterValues { job -> !job.isActive }.keys
        toRemove.forEach { r -> activeStreams.remove(r) }
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

    fun openStream(range: IntRange, scope: CoroutineScope) {
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

    suspend fun tryAdjustStreamsForKey(key: Int, scope: CoroutineScope) = mutex.withLock {
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
                ?: alignedChunkStartForKey(key, baseStart = 0, config)

            val centerChunk = alignedChunkContaining(key, baseStart, totalSize, config)
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
                directionForward && delta < 0 -> Int.MAX_VALUE / 2 + kotlin.math.abs(delta)
                !directionForward && delta <= 0 -> kotlin.math.abs(delta)
                else -> Int.MAX_VALUE / 2 + delta
            }
        })

        sortedToOpen.forEach { range -> openStream(range, scope) }

        previousKey = lastReadKey
        lastReadKey = key
    }

    fun cancelActiveStreams() {
        activeStreams.values.forEach { it.cancel(CancellationException("StreamingPager: flow collection cancelled")) }
        activeStreams.clear()
    }
}
