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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.wwind.paging.core.BuildKonfig
import ua.wwind.paging.core.ExperimentalStreamingPagerApi
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.core.PagingMap

/**
 * Configuration for `StreamingPager`.
 *
 * Defines chunk sizing, preloading and cache behavior, stream-closing policy, and
 * key-access debounce used when adjusting active portion streams.
 *
 * @property loadSize Number of items per portion (chunk) request.
 * @property preloadSize Items to keep preloaded in both directions from the last accessed key.
 * @property cacheSize Cache radius in indices around the last accessed key; values outside are pruned.
 * @property closeThreshold Distance beyond the active window after which a portion flow is closed.
 * @property keyDebounceMs Debounce delay for key-access events before adjusting streams.
 */
public data class StreamingPagerConfig(
    val loadSize: Int = 20,
    val preloadSize: Int = 60,
    val cacheSize: Int = 100,
    val closeThreshold: Int = loadSize,
    val keyDebounceMs: Long = 300,
)

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
 * - When the total size shrinks, out-of-bounds flows are cancelled and cached items are pruned.
 * - Load state is aggregated from per-range states with priority: Loading > Error > Success.
 *
 * @property config Paging behavior configuration (see `StreamingPagerConfig`).
 * @property readTotal Provider of a continuous flow with the current total item count.
 * @property readPortion Provider of a flow for a portion starting at `pos` of length `loadSize`,
 * returning only the map of values keyed by absolute index.
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

        val logger = Logger(
            StaticConfig(
                minSeverity = runCatching { Severity.valueOf(BuildKonfig.LOG_LEVEL) }
                    .getOrDefault(Severity.Debug)
            )
        )

        val state = StreamingPagerState(
            config = config,
            readPortion = readPortion,
            logger = logger,
        )

        val retryRequests: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

        val emitter = launch {
            combine(state.data, state.loadStateFlow) { data: PagingMap<T>, loadState ->
                PagingData(data, loadState) { key ->
                    state.onGet(key)
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
                            state.onTotalChanged(newTotal)
                            if (emptyBefore && newTotal > 0) {
                                state.tryAdjustStreamsForKey(0, this)
                            }
                        }
                    retryRequests.first()
                    state.onTotalRetryStart()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.e(t) { "readTotal: error" }
                    state.onTotalError(t)
                    retryRequests.first()
                    state.onTotalRetryStart()
                }
            }
        }

        val keysJob = launch {
            state.keyTrigger
                .debounce(config.keyDebounceMs)
                .distinctUntilChanged()
                .collect { key ->
                    logger.d { "keyTrigger: key=$key" }
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
    }
}
