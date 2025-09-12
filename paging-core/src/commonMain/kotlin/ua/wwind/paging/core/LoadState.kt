package ua.wwind.paging.core

/**
 * Represents the global loading state of the paging operation.
 *
 * LoadState provides a high-level view of what the Pager is currently doing,
 * allowing UI components to show appropriate feedback to users. Unlike
 * EntryState which represents individual item states, LoadState represents
 * the overall paging operation status.
 *
 * This follows a sealed interface pattern for exhaustive state handling
 * and type safety in reactive UI patterns.
 */
public sealed interface LoadState {
    /**
     * Indicates that a paging operation is currently in progress.
     *
     * This state is active when:
     * - Initial data is being loaded for the first time
     * - Additional data is being fetched due to user scrolling
     * - Retry operations are being executed after an error
     *
     * UI should typically show a loading indicator during this state.
     * Individual items may still be accessible through EntryState.Success
     * for previously loaded data.
     */
    public data object Loading : LoadState

    /**
     * Indicates that a paging operation failed with an error.
     *
     * This state preserves both the error details and the position that
     * triggered the failed load, enabling targeted retry functionality.
     *
     * The paging system remains functional - previously loaded data stays
     * available, and users can retry the failed operation or navigate to
     * different positions that might load successfully.
     *
     * @param throwable The exception that caused the loading failure
     * @param key The position that was being loaded when the error occurred
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