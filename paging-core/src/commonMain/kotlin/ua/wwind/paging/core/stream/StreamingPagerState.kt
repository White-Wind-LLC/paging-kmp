package ua.wwind.paging.core.stream

import co.touchlab.kermit.Logger
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.wwind.paging.core.ConfirmedConflict
import ua.wwind.paging.core.KeyedCache
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.core.PagingMap
import ua.wwind.paging.core.mergeIntoCache
import ua.wwind.paging.core.pruneToRange
import ua.wwind.paging.core.settledRangeAround
import kotlin.concurrent.Volatile

/**
 * Returns this map with [range] set to [state], or this very instance when it already holds it.
 *
 * Every message of a portion stream marks its range `Success`, so after the first one the write
 * changes nothing. Handing the same instance back keeps that case free of an allocation.
 */
internal fun Map<IntRange, LoadState>?.withRangeState(range: IntRange, state: LoadState): Map<IntRange, LoadState> {
    if (this != null && this[range] == state) return this
    return this.orEmpty() + (range to state)
}

/** How long a hidden position's window may stay live and silent before its duplicate counts as real. */
internal const val CONFLICT_GRACE_MS: Long = 1_000L

internal class StreamingPagerState<T>(
    val config: StreamingPagerConfig,
    val readPortion: (pos: Int, loadSize: Int) -> Flow<Map<Int, T>>,
    logger: Logger,
    itemKey: ((T) -> Any)? = null,
) {
    val logger: Logger = logger.withTag("StreamingPager")

    /** Replaces the plain values cache when `itemKey` is set. */
    private val keyed: Keyed? = itemKey?.let { Keyed(KeyedCache(it)) }

    val mutex: Mutex = Mutex()
    val keyTrigger: MutableStateFlow<Int> = MutableStateFlow(0)
    var lastReadKey: Int = 0
    var previousKey: Int = 0

    /**
     * The position the consumer read last, whether or not [settledRange] gated that read.
     *
     * The window is planned around this rather than around the last read that survived the gate:
     * those are decided by the cache, which this pager prunes around the key it plans for, and the
     * loop that closes makes the window flip between the two ends of a wide read span (#45).
     */
    @Volatile
    var lastAccessedKey: Int = 0

    /**
     * Positions whose access cannot move the streamed window; see [settledRangeAround].
     *
     * A page of lead time before the edge of the window is what keeps it shifting ahead of a
     * scroll. Empty until portions have actually arrived, so nothing is suppressed before the cache
     * is known to hold the window.
     */
    val settledRange: MutableStateFlow<IntRange> = MutableStateFlow(IntRange.EMPTY)

    fun onGet(key: Int) {
        lastAccessedKey = key
        // A list re-reads every visible row on each recomposition, so this runs per row per frame.
        // Deep inside the streamed window the answer is always "nothing to do", and the CAS below -
        // which also restarts the debounce - is pure overhead there.
        if (key in settledRange.value) return
        keyTrigger.update { key }
    }

    val data: MutableStateFlow<PagingMap<T>> =
        MutableStateFlow(PagingMap(0, persistentMapOf(), onGet = ::onGet))

    val rangeLoadStates: MutableStateFlow<Map<IntRange, LoadState>?> = MutableStateFlow(null)

    val loadStateFlow: Flow<LoadState> = rangeLoadStates
        .map { stateMap ->
            if (stateMap == null || stateMap.values.contains(LoadState.Loading)) {
                LoadState.Loading
            } else {
                val firstError = stateMap.values.firstOrNull { it is LoadState.Error }
                firstError ?: LoadState.Success
            }
        }
        .onStart { emit(LoadState.Loading) }
        // The map underneath changes on every streamed message; the state derived from it rarely
        // does. Without this, each repeat travels through `combine` as another `PagingData`.
        .distinctUntilChanged()

    val activeStreams: MutableMap<IntRange, Job> = LinkedHashMap()

    /**
     * Cache window the cached values were last constrained to.
     *
     * Everything in `data` is guaranteed to lie inside it: `onPortion` establishes the invariant and
     * `onTotalChanged` only removes keys. While the viewport is stationary the window does not move,
     * so incoming portions can skip the scan over the whole cache and write just their own delta.
     */
    private var prunedCacheRange: IntRange? = null

    suspend fun onPortion(values: Map<Int, T>) {
        mutex.withLock {
            val cacheRange = ((lastReadKey - config.cacheSize)..(lastReadKey + config.cacheSize))
            val pruneExisting = prunedCacheRange != cacheRange
            val keyCountBefore = data.value.values.size
            data.update { current ->
                val merged = current.values.mergeIntoCache(values, cacheRange, pruneExisting)
                if (merged === current.values) {
                    current
                } else {
                    PagingMap(
                        size = current.size,
                        values = merged,
                        onGet = ::onGet,
                    )
                }
            }
            prunedCacheRange = cacheRange
            // A portion that only carries new values for positions already cached leaves the
            // settled range exactly where it was, and re-scanning the cache for it would put an
            // O(cache) walk on the hot path of a live stream.
            if (pruneExisting || data.value.values.size != keyCountBefore) {
                settledRange.value = data.value.settledRangeAround(lastReadKey, margin = config.loadSize)
            }
            null
        }
    }

    suspend fun removeStreamByRange(range: IntRange, stream: Job) = withContext(NonCancellable) {
        mutex.withLock {
            activeStreams.remove(range)
            keyed?.streamEndedLocked(stream)
            rangeLoadStates.update { current: Map<IntRange, LoadState>? ->
                // A stream that ended in an error keeps its state: it is the only record the
                // consumer has of the failure, and dropping it here reported the list as an empty
                // `Success`. A retry reopens the range and overwrites it with `Loading`.
                current?.filterNot { it.key == range && it.value !is LoadState.Error }
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
                    logger.d {
                        "onPortion(range=$range, count=${values.size}, " +
                            "keys=${values.keys.minOrNull()}..${values.keys.maxOrNull()})"
                    }
                    if (keyed == null) onPortion(values) else keyed.onPortion(values, coroutineContext.job, scope)
                    rangeLoadStates.update { current: Map<IntRange, LoadState>? ->
                        current.withRangeState(range, LoadState.Success)
                    }
                }
            } catch (e: CancellationException) {
                logger.d { "openStream: cancelled range=$range" }
                throw e
            } catch (t: Throwable) {
                logger.e(t) { "openStream: error in range=$range" }
                rangeLoadStates.update { current: Map<IntRange, LoadState>? ->
                    current.withRangeState(range, LoadState.Error(t, range.first))
                }
            } finally {
                removeStreamByRange(range, coroutineContext.job)
            }
        }
        activeStreams[range] = job
    }

    suspend fun onTotalChanged(newTotalRaw: Int, scope: CoroutineScope) = mutex.withLock {
        val newTotal = newTotalRaw.coerceAtLeast(0)
        val current = data.value
        if (current.size == newTotal) return@withLock
        logger.d { "totalSize changed: ${current.size} -> $newTotal" }

        val newRange = 0..<newTotal.coerceAtLeast(1)
        // Only removes keys, so the `prunedCacheRange` invariant survives untouched.
        if (keyed == null) {
            val prunedValues = current.values.pruneToRange(newRange)
            data.update {
                PagingMap(
                    size = newTotal,
                    values = prunedValues,
                    onGet = ::onGet,
                )
            }
        } else {
            keyed.pruneLocked(newRange, newTotal)
        }
        settledRange.value = data.value.settledRangeAround(lastReadKey, margin = config.loadSize)

        // Valid indices are `0..<newTotal`, so a range is out of bounds as soon as it touches
        // `newTotal`. For a non-empty range `first <= last`, so testing `last` covers both ends.
        val toClose = activeStreams.keys.filter { r -> r.last >= newTotal }
        if (toClose.isNotEmpty()) logger.d { "closing due to total shrink: $toClose" }
        toClose.forEach { r ->
            withContext(NonCancellable) {
                activeStreams.remove(r)?.cancel(CancellationException("StreamingPager: total shrank"))
                rangeLoadStates.update { currentStates: Map<IntRange, LoadState>? ->
                    currentStates?.filterNot { it.key == r }
                }
            }
        }
        // `lastReadKey == newTotal` is already past the end, hence `>=`. The last valid index is
        // `newTotal - 1`; on an empty total we fall back to 0 rather than a negative key.
        val lastValidKey = (newTotal - 1).coerceAtLeast(0)
        val keyFellOffTheEnd = lastReadKey >= newTotal
        if (keyFellOffTheEnd) lastAccessedKey = lastValidKey

        // A range the new end only clipped keeps its rows cached, so `settledRange` swallows every
        // later access to them and nothing would reopen the part that survived; replanning covers
        // it. An emptied list has nothing left to cover, so its streams stay closed.
        if (newTotal > 0 && (toClose.isNotEmpty() || keyFellOffTheEnd)) {
            adjustStreamsForKeyLocked(lastAccessedKey.coerceIn(0, lastValidKey), scope)
        }
    }

    /**
     * Puts every known range into the state [stateFor] returns for it.
     *
     * Used to propagate a global `readTotal()` outcome - an error, or the retry that follows it -
     * to the UI without tearing the flow down. When nothing is known yet the first page stands in,
     * so that an initial failure is still visible.
     */
    suspend fun markKnownRanges(stateFor: (IntRange) -> LoadState) = mutex.withLock {
        val ranges = (rangeLoadStates.value.orEmpty().keys + activeStreams.keys).toSet()
        val knownRanges = ranges.ifEmpty {
            setOf(0..<config.loadSize)
        }
        rangeLoadStates.update { current: Map<IntRange, LoadState>? ->
            current.orEmpty() + knownRanges.associateWith(stateFor)
        }
    }

    suspend fun tryAdjustStreamsForKey(key: Int, scope: CoroutineScope) = mutex.withLock {
        logger.d { "tryAdjustStreamsForKey: key=$key" }
        adjustStreamsForKeyLocked(key, scope)
    }

    /**
     * Reconciles the open streams with the window [key] calls for. Expects [mutex] to be held.
     */
    private fun adjustStreamsForKeyLocked(key: Int, scope: CoroutineScope) {
        // A stream that has already ended clears its own entry, but only once `removeStreamByRange`
        // gets the mutex this call is holding - so drop the dead ones before planning around them.
        val finished = activeStreams.filterValues { job -> !job.isActive }.keys
        finished.forEach { r -> activeStreams.remove(r) }

        val directionForward = key > lastReadKey

        val totalSize = data.value.size
        logger.d { "adjust: key=$key last=$lastReadKey total=$totalSize forward=$directionForward" }

        val plan = planStreamWindow(activeStreams.keys, key, totalSize, config)
        plan.window?.let { window -> closeStreamsOutsideWindowLocked(window) }
        openMissingStreamsLocked(plan.chunks, key, directionForward, scope)

        previousKey = lastReadKey
        lastReadKey = key
        settledRange.value = data.value.settledRangeAround(lastReadKey, margin = config.loadSize)
    }

    /**
     * Cancels the streams that sit further than [StreamingPagerConfig.closeThreshold] outside
     * [window]. Expects [mutex] to be held.
     */
    private fun closeStreamsOutsideWindowLocked(window: IntRange) {
        val toCloseNow = activeStreams.keys.filter { r ->
            distanceBeyondWindow(window, r) > config.closeThreshold
        }
        if (toCloseNow.isNotEmpty()) {
            logger.d {
                "closing: $toCloseNow (window=$window, threshold>${config.closeThreshold})"
            }
        }
        toCloseNow.forEach { r ->
            activeStreams.remove(r)?.cancel(CancellationException("StreamingPager: window shifted"))
            rangeLoadStates.update { current: Map<IntRange, LoadState>? ->
                current?.filterNot { it.key == r }
            }
        }
    }

    /**
     * Opens the chunks of [targetChunks] that are not streaming yet, nearest to [key] first and
     * favouring the scroll direction. Expects [mutex] to be held.
     */
    private fun openMissingStreamsLocked(
        targetChunks: List<IntRange>,
        key: Int,
        directionForward: Boolean,
        scope: CoroutineScope,
    ) {
        // An empty range has nothing to fetch, so `openStream` would drop it - marking it here would
        // leave a `Loading` entry nobody ever clears.
        val toOpen = targetChunks.filterNot { it.isEmpty() }.filter { it !in activeStreams }
        if (toOpen.isNotEmpty()) {
            logger.d { "opening missing ranges: $toOpen" }
            rangeLoadStates.update { current: Map<IntRange, LoadState>? ->
                current.orEmpty() + toOpen.associateWith { LoadState.Loading }
            }
        }

        val anchor = targetChunks.firstOrNull { key in it } ?: targetChunks.firstOrNull()
        val sortedToOpen = if (anchor == null) {
            toOpen
        } else {
            toOpen.sortedBy { openOrderKey(it, anchor, directionForward) }
        }

        sortedToOpen.forEach { range -> openStream(range, scope) }
    }

    fun cancelActiveStreams() {
        activeStreams.values.forEach { it.cancel(CancellationException("StreamingPager: flow collection cancelled")) }
        activeStreams.clear()
    }

    /** The `itemKey` path: writes through a [KeyedCache] and confirms real duplicates. Guarded by [mutex]. */
    private inner class Keyed(private val cache: KeyedCache<T>) {
        private var publishedKeys: PersistentMap<Int, Any> = persistentMapOf()

        /** Pending grace timers by hidden position. */
        private val graceTimers: MutableMap<Int, Job> = HashMap()

        /** Live portion streams that have emitted at least once. */
        private val emittedStreams: MutableSet<Job> = HashSet()

        suspend fun onPortion(values: Map<Int, T>, stream: Job, scope: CoroutineScope) {
            mutex.withLock {
                emittedStreams += stream
                val cacheRange = ((lastReadKey - config.cacheSize)..(lastReadKey + config.cacheSize))
                val pruneExisting = prunedCacheRange != cacheRange
                val visibleCountBefore = cache.visible.size
                val result = cache.write(values, cacheRange, pruneExisting, confirmOnRewrite = true)
                prunedCacheRange = cacheRange
                publishLocked(data.value.size)
                result.confirmed.forEach(::logConfirmed)
                if (pruneExisting || cache.visible.size != visibleCountBefore || result.newlyHidden.isNotEmpty()) {
                    settledRange.value = data.value.settledRangeAround(lastReadKey, margin = config.loadSize)
                }
                result.newlyHidden.forEach { position -> startGraceTimerLocked(position, scope) }
            }
        }

        /** Drops positions outside [range] and publishes with [size]. Expects [mutex] to be held. */
        fun pruneLocked(range: IntRange, size: Int) {
            cache.prune(range)
            publishLocked(size)
        }

        /** Publishes [cache] unless it is already what [data] holds. */
        private fun publishLocked(size: Int) {
            val current = data.value
            if (current.size == size && current.values === cache.visible && publishedKeys === cache.keys) return
            publishedKeys = cache.keys
            data.value = PagingMap(size, cache.visible, ::onGet, cache.keys)
        }

        fun streamEndedLocked(stream: Job) {
            emittedStreams -= stream
        }

        /**
         * Confirms the conflict hiding [position] once its window has stayed live for
         * [CONFLICT_GRACE_MS] without re-emitting. A closed window keeps it hidden until it reopens,
         * and a reopened one until it emits: before that it has said nothing about the position.
         */
        private fun startGraceTimerLocked(position: Int, scope: CoroutineScope) {
            graceTimers.remove(position)?.cancel()
            graceTimers[position] = scope.launch {
                delay(CONFLICT_GRACE_MS)
                mutex.withLock {
                    graceTimers.remove(position)
                    val windowLive = activeStreams.any { (range, job) ->
                        position in range && job.isActive && job in emittedStreams
                    }
                    if (!windowLive) return@withLock
                    val conflict = cache.confirm(position) ?: return@withLock
                    logConfirmed(conflict)
                    publishLocked(data.value.size)
                    settledRange.value = data.value.settledRangeAround(lastReadKey, margin = config.loadSize)
                }
            }
        }

        private fun logConfirmed(c: ConfirmedConflict) {
            logger.w { "itemKey ${c.key} is at positions ${c.positions} in the source; showing all of them" }
        }
    }
}
