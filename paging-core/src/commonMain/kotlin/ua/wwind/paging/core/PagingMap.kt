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
 * Item keys ([keyAt]) are supplied only by the pagers, so they stay unique in every map a consumer
 * sees; a map built through the public constructor has fallback keys only.
 *
 * @param V The type of values stored in the map
 * @property size Total number of items in the complete dataset
 * @property values Map of loaded items (position -> item)
 * @param onGet Callback invoked when an item is accessed (triggers loading)
 */
public class PagingMap<V> internal constructor(
    public val size: Int,
    public val values: PersistentMap<Int, V>,
    private val onGet: (key: Int) -> Unit,
    // Owners' item keys by position; a position without an entry uses the fallback key.
    private val keys: PersistentMap<Int, Any>,
) {

    /** Creates a map whose keys are all fallbacks; allows a trailing `onGet` lambda. */
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
     * Returns a copy with the given properties replaced.
     *
     * A position keeps its item key only while it is inside the new [size] and holds an equal value;
     * the rest fall back, so a copy never pairs a key with another item.
     */
    public fun copy(
        size: Int = this.size,
        values: PersistentMap<Int, V> = this.values,
        onGet: (key: Int) -> Unit = this.onGet,
    ): PagingMap<V> {
        val keptKeys = if (values === this.values && size >= this.size) {
            keys
        } else {
            keys.filter { (position, _) ->
                position < size && position in values && values[position] == this.values[position]
            }.toPersistentMap()
        }
        return PagingMap(size, values, onGet, keptKeys)
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
     * position when no `itemKey` is set, get the fallback `FALLBACK_KEY_PREFIX + index`; so do
     * the extra rows of a key confirmed to appear several times in the source.
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PagingMap<*>) return false
        return size == other.size && values == other.values && onGet == other.onGet && keys == other.keys
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + values.hashCode()
        result = 31 * result + onGet.hashCode()
        result = 31 * result + keys.hashCode()
        return result
    }

    override fun toString(): String = "PagingMap(size=$size, values=$values)"
}
