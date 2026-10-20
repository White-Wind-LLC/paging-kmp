package ua.wwind.paging.core.stream

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import ua.wwind.paging.core.BuildKonfig
import ua.wwind.paging.core.ExperimentalStreamingPagerApi
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.core.PagingMap
import ua.wwind.paging.core.cacheRadiusTooSmallMessage

/**
 * Configuration for `StreamingPager`.
 *
 * Defines chunk sizing, preloading and cache behavior, stream-closing policy, and
 * key-access debounce used when adjusting active portion streams.
 *
 * `preloadSize` and `cacheSize` are both radii in indices around the last accessed key, and
 * `cacheSize >= preloadSize` is required: the cache has to be able to hold what the preload window
 * streams, otherwise portions are discarded the moment they arrive while their streams stay open.
 * Because the chunk grid is aligned to `loadSize`, the streamed window reaches slightly past the
 * preload radius - leave `cacheSize >= preloadSize + loadSize` to retain that overshoot as well.
 *
 * @property loadSize Number of items per portion (chunk) request.
 * @property preloadSize Preload radius in indices around the last accessed key, in both directions.
 * @property cacheSize Cache radius in indices around the last accessed key; values outside are pruned,
 * so the cache holds up to `2 * cacheSize + 1` items. Must be `>= preloadSize`.
 * @property closeThreshold Distance beyond the active window after which a portion flow is closed.
 * @property keyDebounceMs Debounce delay for key-access events before adjusting streams.
 */
public data class StreamingPagerConfig(
    val loadSize: Int = 20,
    val preloadSize: Int = 60,
    val cacheSize: Int = 100,
    val closeThreshold: Int = loadSize,
    val keyDebounceMs: Long = 300,
) {
    // Validated here rather than in `StreamingPager` so that `copy()` is covered too.
    init {
        require(loadSize > 0) { "loadSize must be > 0" }
        require(preloadSize >= 0) { "preloadSize must be >= 0" }
        require(cacheSize >= preloadSize) {
            cacheRadiusTooSmallMessage(cacheSize, preloadName = "preloadSize", preloadSize = preloadSize)
        }
        require(closeThreshold >= 0) { "closeThreshold must be >= 0" }
        require(keyDebounceMs >= 0) { "keyDebounceMs must be >= 0" }
    }
}

/**
 * Streaming pager that manages multiple concurrent, chunk-aligned portion flows together with a
 * dedicated total-size stream, exposing a single `Flow<PagingData<T>>` for UI consumption.
 *
 * Behavior:
 * - `readTotal()` is a continuous flow of the total item count; it drives `PagingMap.size` and pruning.
 * - `readPortion(start, size)` emits maps of index-to-item for the requested range; no totals are emitted.
 * - The pager opens and keeps portion flows for chunks of size `config.loadSize` inside an active window
 *   centered around the last accessed key, preloading up to `config.preloadSize` in both directions.
 * - Flows are closed when they move farther than `config.closeThreshold` beyond the active window.
 * - When the total size shrinks, out-of-bounds flows are cancelled and cached items are pruned; a flow the new
 *   end only clipped is reopened for the part of its range that survives.
 * - Load state is aggregated from per-range states with priority: Loading > Error > Success.
 *
 * @property config Paging behavior configuration (see `StreamingPagerConfig`).
 * @property readTotal Provider of a continuous flow with the current total item count.
 * @property itemKey Optional stable key of an item, exposed through `PagingMap.keyAt` (for example as
 * the `LazyColumn` key). Must return a type a Bundle can store - `Uuid`, `String` or a primitive, not
 * a boxed value class - and never a `String` starting with `PagingMap.FALLBACK_KEY_PREFIX`. Keys
 * should be unique in the source. When one key shows up at several positions, the last written one is
 * shown and the others are hidden: a transient duplicate (an item that moved between windows that
 * have not all re-emitted yet) resolves as soon as the stale window catches up. A real duplicate is
 * confirmed when the hidden position's window re-emits the same key there, or stays live but silent
 * for a second; all its positions are then shown, extra ones under fallback keys, and a warning is
 * logged. With `null`, every key is a fallback and nothing is hidden.
 * @property readPortion Provider of a flow for a portion starting at `pos` of length `loadSize`,
 * returning only the map of values keyed by absolute index.
 */
@ExperimentalStreamingPagerApi
@OptIn(FlowPreview::class)
public class StreamingPager<T>(
    private val config: StreamingPagerConfig = StreamingPagerConfig(),
    private val readTotal: () -> Flow<Int>,
    private val itemKey: ((T) -> Any)? = null,
    private val readPortion: (pos: Int, loadSize: Int) -> Flow<Map<Int, T>>,
) {
    /** Source-compatible constructor for positional calls without `itemKey`. */
    public constructor(
        config: StreamingPagerConfig,
        readTotal: () -> Flow<Int>,
        readPortion: (pos: Int, loadSize: Int) -> Flow<Map<Int, T>>,
    ) : this(config, readTotal, null, readPortion)

    /**
     * Public flow of paging state for the UI. Jobs are bound to the collection lifecycle.
     *
     * The flow is conflated: `PagingData` is a complete snapshot of the list, so a collector that
     * falls behind a live stream is handed the newest state rather than every state it missed.
     */
    public val flow: Flow<PagingData<T>> = channelFlow {

        val logger = Logger(
            StaticConfig(
                minSeverity = runCatching { Severity.valueOf(BuildKonfig.LOG_LEVEL) }
                    .getOrDefault(Severity.Debug),
            ),
        )

        val state = StreamingPagerState(
            config = config,
            readPortion = readPortion,
            logger = logger,
            itemKey = itemKey,
        )

        val retryRequests: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

        // Own channel: the access pipeline drops repeats, so a retry for the position the consumer
        // is already sitting on would be swallowed there.
        val retryKeys: MutableSharedFlow<Int> = MutableSharedFlow(extraBufferCapacity = 64)

        val emitter = launch {
            combine(state.data, state.loadStateFlow) { data: PagingMap<T>, loadState ->
                PagingData(data, loadState) { key ->
                    // Not through `onGet`: a retry has to reach the planner even from inside the
                    // settled range.
                    retryKeys.tryEmit(key)
                    retryRequests.tryEmit(Unit)
                }
            }.collect { paging ->
                send(paging)
            }
        }

        val totalJob = launch {
            while (true) {
                try {
                    readTotal()
                        .distinctUntilChanged()
                        .collect { newTotal ->
                            val emptyBefore = state.data.value.size == 0
                            state.onTotalChanged(newTotal, this)
                            if (emptyBefore && newTotal > 0) {
                                state.tryAdjustStreamsForKey(0, this)
                            }
                        }
                    retryRequests.first()
                    state.markKnownRanges { LoadState.Loading }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.e(t) { "readTotal: error" }
                    state.markKnownRanges { range -> LoadState.Error(t, range.first) }
                    retryRequests.first()
                    state.markKnownRanges { LoadState.Loading }
                }
            }
        }

        val keysJob = launch {
            merge(
                state.keyTrigger
                    .debounce(config.keyDebounceMs)
                    // The trigger only says that something uncached was read; plan for where the
                    // consumer is, not for whichever read the cache let through (#45).
                    .map { state.lastAccessedKey }
                    .distinctUntilChanged(),
                retryKeys,
            ).collect { key ->
                logger.d { "adjust requested: key=$key" }
                if (key < 0) return@collect
                state.tryAdjustStreamsForKey(key, this)
            }
        }

        awaitClose {
            emitter.cancel()
            totalJob.cancel()
            keysJob.cancel()
            state.cancelActiveStreams()
        }
    }.conflate()
}
