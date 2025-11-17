package ua.wwind.paging.core

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap

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
    val values: PersistentMap<Int, V>,
    private val onGet: (key: Int) -> Unit
) {

    public companion object {
        /**
         * Creates an empty PagingMap with no data and no loading capability
         * Used as initial state or for testing
         */
        public fun <V> empty(): PagingMap<V> {
            return PagingMap(0, persistentMapOf()) {}
        }
    }
    /**
     * Transforms the values in this PagingMap using the provided transformation function.
     *
     * Creates a new PagingMap with the same size and loading behavior, but with
     * all currently loaded values transformed to type R. The transformation is applied
     * only to items that are currently in memory, not to items that might be loaded later.
     *
     * @param transform Function to convert values from type V to type R
     * @return New PagingMap with transformed values
     */
    public fun <R> mapValues(transform: (V) -> R): PagingMap<R> {
        return PagingMap(size = size, values = values.mapValues { (_, value) -> transform(value) }.toPersistentMap(), onGet = onGet)
    }

    /**
     * Access operator that returns the state of an item at the given position.
     *
     * This is the core method that:
     * 1. Notifies the Pager that this position is being accessed (triggers loading)
     * 2. Returns Success if the item is loaded, Loading if it's not yet available
     *
     * @param key The position of the item to access
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
     * @return The minimum loaded position, or -1 if no data is loaded
     */
    public fun firstKey(): Int {
        return values.keys.minOfOrNull { it } ?: -1
    }

    /**
     * Gets the largest position that has been loaded into memory
     * Useful for UI optimizations and understanding data coverage
     *
     * @return The maximum loaded position, or -1 if no data is loaded
     */
    public fun lastKey(): Int {
        return values.keys.maxOfOrNull { it } ?: -1
    }
}