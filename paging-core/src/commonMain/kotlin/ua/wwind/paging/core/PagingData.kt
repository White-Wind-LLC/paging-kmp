package ua.wwind.paging.core

/**
 * Immutable snapshot of paging state at a point in time.
 *
 * PagingData is the main interface between the Pager and consuming UI components.
 * It provides everything needed to display paged data and handle loading states:
 *
 * - Access to the data through PagingMap
 * - Current loading state for global UI feedback
 * - Retry functionality for error recovery
 *
 * This class is designed to be collected from the Pager's flow and used directly
 * in reactive UI frameworks like Jetpack Compose or other reactive systems.
 *
 * Key characteristics:
 * - Immutable: Each emission represents a new state
 * - Complete: Contains all information needed for paging UI
 * - Reactive: Designed for flow-based reactive programming
 *
 * @param T The type of items in the paged data
 * @param data The PagingMap containing loaded items and access logic
 * @param loadState Current global loading state (Loading, Success, or Error)
 * @param retry Function to retry loading for a specific position
 */
public data class PagingData<T>(
    val data: PagingMap<T>,
    val loadState: LoadState,
    val retry: (key: Int) -> Unit
) {
    public companion object {
        /**
         * Creates an empty PagingData instance with no items and success state.
         *
         * This is useful for:
         * - Initial state before any data is loaded
         * - Testing scenarios
         * - Placeholder state in UI
         * - Fallback when data sources are unavailable
         *
         * The retry function is a no-op since there's no loading operation to retry.
         *
         * @return Empty PagingData instance
         */
        public fun <T> empty(): PagingData<T> {
            return PagingData(PagingMap.empty(), LoadState.Success) {}
        }
    }
}