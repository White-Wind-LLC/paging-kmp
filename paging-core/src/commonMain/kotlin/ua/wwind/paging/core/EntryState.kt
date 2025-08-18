package ua.wwind.paging.core

/**
 * Represents the loading state of an individual item in the paged data.
 *
 * EntryState follows a sealed interface pattern to provide type-safe handling
 * of item states in reactive UI frameworks. This allows consumers to:
 * - Show loading placeholders for items not yet loaded
 * - Display content for successfully loaded items
 * - Handle different states with exhaustive when expressions
 *
 * The design ensures that unloaded items can be represented without consuming
 * memory for their actual content, while loaded items provide immediate access
 * to their data.
 *
 * @param T The type of the item being represented
 */
public sealed interface EntryState<out T> {
    /**
     * Indicates that an item is currently being loaded or waiting to be loaded.
     *
     * This state is returned when:
     * - An item is requested but not yet in the cache
     * - The Pager is actively loading data for this position
     * - The item exists in the dataset but loading hasn't started yet
     *
     * UI components should show loading placeholders in this state.
     */
    public data object Loading : EntryState<Nothing>

    /**
     * Indicates that an item has been successfully loaded and is available for use.
     *
     * This state contains the actual item data and is returned when:
     * - The item was previously loaded and is in the cache
     * - A loading operation completed successfully for this item
     *
     * @param value The loaded item data
     */
    public data class Success<T>(val value: T) : EntryState<T>
}

/**
 * Extension function to safely extract the value from an EntryState.
 *
 * This provides a convenient way to get the actual item when you only care
 * about successfully loaded items and want to ignore loading states.
 *
 * Usage:
 * ```kotlin
 * val item = pagingData.data[position].getOrNull()
 * if (item != null) {
 *     displayItem(item)
 * } else {
 *     showLoadingPlaceholder()
 * }
 * ```
 *
 * @return The item value if this is Success state, null if Loading
 */
public fun <T> EntryState<T>.getOrNull(): T? = if (this is EntryState.Success<T>) value else null