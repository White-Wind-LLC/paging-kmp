package ua.wwind.paging.core

/**
 * Smart data map that provides lazy loading access to paged data.
 *
 * PagingMap acts as a bridge between the UI and the Pager, automatically
 * triggering data loading when items are accessed. It maintains:
 * - Total size of the dataset for UI layout calculations
 * - Currently loaded items in a sparse map structure
 * - Callback mechanism to notify Pager of data access patterns
 *
 * Key features:
 * - O(1) access time for loaded items
 * - Automatic loading trigger for unloaded items
 * - Memory-efficient sparse storage (only loaded items consume memory)
 * - Immutable design for reactive programming patterns
 *
 * @param V The type of values stored in the map
 * @param size Total number of items in the complete dataset
 * @param values Map of loaded items (position -> item)
 * @param onGet Callback invoked when an item is accessed (triggers loading)
 */
public data class PagingMap<V>(
    val size: Int,
    val values: Map<Int, V>,
    private val onGet: (key: Int) -> Unit
) {

    public companion object {
        /**
         * Creates an empty PagingMap with no data and no loading capability
         * Used as initial state or for testing
         */
        public fun <V> empty(): PagingMap<V> {
            return PagingMap(0, emptyMap()) {}
        }
    }

    /**
     * Access operator that returns the state of an item at the given position.
     *
     * This is the core method that:
     * 1. Notifies the Pager that this position is being accessed (triggers loading)
     * 2. Returns Success if the item is loaded, Loading if it's not yet available
     *
     * The method uses 1-based indexing to match common UI patterns where
     * the first item is at position 1, not 0.
     *
     * @param key The 1-based position of the item to access
     * @return EntryState.Success with the item if loaded, EntryState.Loading if not
     */
    public operator fun get(key: Int): EntryState<V> {
        onGet(key)  // Notify Pager about data access - may trigger loading
        return values[key]?.let {
            EntryState.Success(it) // Item is loaded and available
        } ?: EntryState.Loading    // Item is not loaded yet, loading may be triggered
    }

    /**
     * Checks if the dataset is empty (no items exist in the source)
     * This is different from having no loaded items - size represents
     * the total available items, not the currently loaded ones
     *
     * @return true if the total dataset size is 0
     */
    public fun isEmpty(): Boolean {
        return size == 0
    }

    /**
     * Gets the smallest position that has been loaded into memory
     * Useful for UI optimizations and understanding data coverage
     *
     * @return The minimum loaded position, or 0 if no data is loaded
     */
    public fun firstKey(): Int {
        return values.keys.minOfOrNull { it } ?: 0
    }

    /**
     * Gets the largest position that has been loaded into memory
     * Useful for UI optimizations and understanding data coverage
     *
     * @return The maximum loaded position, or 0 if no data is loaded
     */
    public fun lastKey(): Int {
        return values.keys.maxOfOrNull { it } ?: 0
    }
}