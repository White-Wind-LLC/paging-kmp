package ua.wwind.paging.core

import kotlinx.collections.immutable.PersistentMap

/**
 * Merges [incoming] into this cache and constrains the result to [cacheRange].
 *
 * The naive `(this + incoming).filterKeys { it in cacheRange }.toPersistentMap()` costs three full
 * copies of the cache on every call, so updating a single row is as expensive as replacing the whole
 * window. This variant applies the changes through a single builder, which keeps the persistent map's
 * structural sharing intact and makes the cost proportional to the delta rather than to the cache.
 *
 * @param incoming Values to write; entries outside [cacheRange] are ignored.
 * @param cacheRange Window of positions the cache is allowed to hold.
 * @param pruneExisting Whether already cached keys have to be checked against [cacheRange]. Pass
 * `false` when the caller knows every cached key is still inside the window - for example when
 * [cacheRange] has not moved since the previous merge - to skip the scan over the whole cache.
 * @return The updated cache, or this very instance when nothing had to change.
 */
internal fun <T> PersistentMap<Int, T>.mergeIntoCache(
    incoming: Map<Int, T>,
    cacheRange: IntRange,
    pruneExisting: Boolean = true,
): PersistentMap<Int, T> {
    val stale = if (pruneExisting) keys.filter { it !in cacheRange } else emptyList()
    if (stale.isEmpty() && incoming.isEmpty()) return this

    val builder = builder()
    for (key in stale) builder.remove(key)
    for ((key, value) in incoming) if (key in cacheRange) builder[key] = value
    return builder.build()
}

/**
 * Drops every cached key outside [range], keeping the remaining entries untouched.
 *
 * @return The pruned cache, or this very instance when every key is already inside [range].
 */
internal fun <T> PersistentMap<Int, T>.pruneToRange(range: IntRange): PersistentMap<Int, T> =
    mergeIntoCache(incoming = emptyMap(), cacheRange = range, pruneExisting = true)
