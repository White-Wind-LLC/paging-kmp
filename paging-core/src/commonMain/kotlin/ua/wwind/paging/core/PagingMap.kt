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
 * @param keys Keys of the positions that won their item key; positions hidden by a key
 * conflict are absent from [values] and have no entry here
 */
public data class PagingMap<V>(
    val size: Int,
    val values: PersistentMap<Int, V>,
    private val onGet: (key: Int) -> Unit,
    private val keys: PersistentMap<Int, Any> = persistentMapOf(),
) {

    /** Source-compatible constructor that allows a trailing `onGet` lambda; all keys are fallbacks. */
    public constructor(
        size: Int,
        values: PersistentMap<Int, V>,
        onGet: (key: Int) -> Unit,
    ) : this(size, values, onGet, persistentMapOf())

    public companion object {
        /** Prefix of the fallback key `"ua.wwind.paging#" + index`; cannot equal a user key of another type. */
        public const val FALLBACK_KEY_PREFIX: String = "ua.wwind.paging#"

        /**
         * Creates an empty PagingMap with no data and no loading capability
         * Used as initial state or for testing
         */
        public fun <V> empty(): PagingMap<V> = PagingMap(0, persistentMapOf()) {}
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
    public fun <R> mapValues(transform: (V) -> R): PagingMap<R> = PagingMap(
        size = size,
        values = values.mapValues { (_, value) ->
            transform(value)
        }.toPersistentMap(),
        onGet = onGet,
        keys = keys,
    )

    /**
     * Stable key of the item at [index] for `LazyColumn` or `rowKey`.
     *
     * Never calls `onGet`, so it does not trigger loading. Unique within this map only
     * (two pagers in one list are not supported). Unloaded or hidden positions, and every
     * position when no `itemKey` is set, get the fallback `FALLBACK_KEY_PREFIX + index`.
     */
    public fun keyAt(index: Int): Any = keys[index] ?: (FALLBACK_KEY_PREFIX + index)

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
        onGet(key) // Notify Pager about data access - may trigger loading
        return values[key]?.let {
            EntryState.Success(it) // Item is loaded and available
        } ?: EntryState.Loading // Item is not loaded yet, loading may be triggered
    }

    /**
     * Checks if the dataset is empty (no items exist in the source)
     * This is different from having no loaded items - size represents
     * the total available items, not the currently loaded ones
     *
     * @return true if the total dataset size is 0
     */
    public fun isEmpty(): Boolean = size == 0

    /**
     * Gets the smallest position that has been loaded into memory
     * Useful for UI optimizations and understanding data coverage
     *
     * @return The minimum loaded position, or -1 if no data is loaded
     */
    public fun firstKey(): Int = values.keys.minOfOrNull { it } ?: -1

    /**
     * Gets the largest position that has been loaded into memory
     * Useful for UI optimizations and understanding data coverage
     *
     * @return The maximum loaded position, or -1 if no data is loaded
     */
    public fun lastKey(): Int = values.keys.maxOfOrNull { it } ?: -1
}
