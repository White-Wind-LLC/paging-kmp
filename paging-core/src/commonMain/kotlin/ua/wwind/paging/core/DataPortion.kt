package ua.wwind.paging.core

/**
 * Container for a portion of data loaded from a data source.
 *
 * DataPortion represents the result of a single loading operation from
 * the data source (API, database, file, etc.). It contains both the
 * actual loaded items and metadata about the complete dataset.
 *
 * This class serves as the contract between the Pager and data sources,
 * defining exactly what information is needed for effective paging:
 *
 * - Total size enables proper UI layout (scrollbar sizing, item counting)
 * - Values map provides the actual loaded data with position information
 * - Position-based mapping allows for sparse data loading
 *
 * Design considerations:
 * - Uses Map<Int, T> for O(1) lookup performance
 * - Positions are 1-based to match common UI patterns
 * - Supports sparse data (gaps in positions are allowed)
 * - Immutable design for thread safety
 *
 * @param T The type of items being paged
 * @param totalSize Total number of items available in the complete dataset
 * @param values Map of position (1-based) to loaded items
 */
public data class DataPortion<T>(
    val totalSize: Int,
    val values: Map<Int, T>,
)