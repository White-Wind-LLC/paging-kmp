package ua.wwind.paging.core

/**
 * Represents the loading state of a paging operation.
 *
 * [PagingData] carries two of these. `loadState` describes the accessed position only:
 * `Loading` while the item there is missing and being fetched, `Error` when that fetch
 * fails, `Success` otherwise (scrolling over cached data never changes it).
 * `prefetchState` describes the background fill of the rest of the preload window
 * (`Loading > Error > Success`) and never affects `loadState`.
 *
 * This follows a sealed interface pattern for exhaustive state handling
 * and type safety in reactive UI patterns.
 */
public sealed interface LoadState {
    /**
     * Indicates that a paging operation is currently in progress.
     *
     * As `loadState` this is active only while the item at the accessed position is
     * not cached and a fetch covering it is in flight: the first load, a refresh, or
     * a jump into an uncached area. As `prefetchState` it means the rest of the window
     * is still being filled.
     *
     * Individual items may still be accessible through EntryState.Success
     * for previously loaded data.
     */
    public data object Loading : LoadState

    /**
     * Indicates that a paging operation failed with an error.
     *
     * This state preserves the error details and the current accessed position,
     * enabling targeted retry: `retry(error.key)` re-plans the window where the
     * user is.
     *
     * The paging system remains functional - previously loaded data stays
     * available, and users can retry the failed operation or navigate to
     * different positions that might load successfully.
     *
     * @param throwable The exception that caused the loading failure
     * @param key The current accessed position
     */
    public data class Error(val throwable: Throwable, val key: Int) : LoadState

    /**
     * Indicates that the most recent paging operation completed successfully.
     *
     * This state means:
     * - All requested data has been loaded without errors
     * - The cache has been updated with new items
     * - The system is ready for the next loading operation
     *
     * Note: Success doesn't mean ALL data is loaded, just that the most
     * recent loading request completed successfully. There may still be
     * unloaded items in other parts of the dataset.
     */
    public data object Success : LoadState
}
