package ua.wwind.paging.core

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * Position cache that keeps item keys unique across the published positions.
 *
 * When one key lands on several positions, the most recently written one owns it; the others are
 * hidden until the conflict is confirmed. Hidden values are kept rather than deleted, so a loser is
 * restored as soon as the owner is overwritten or pruned. Every operation costs in proportion to the
 * positions it touches (plus the positions sharing their keys); only `pruneExisting` scans the cache.
 *
 * Not thread-safe: callers hold their pager's mutex.
 */
internal class KeyedCache<T>(private val itemKey: (T) -> Any) {

    private class KeyState(var owner: Int) {
        // Write order, oldest first: ownership falls back to the last one.
        val positions = LinkedHashSet<Int>()
        var confirmed = false
    }

    /** Everything held, hidden positions included. */
    var values: PersistentMap<Int, T> = persistentMapOf()
        private set

    /** Positions that are published; goes to `PagingMap.values`. */
    var visible: PersistentMap<Int, T> = persistentMapOf()
        private set

    /** Owners' keys; goes to `PagingMap.keys`. Confirmed losers are absent and use the fallback key. */
    var keys: PersistentMap<Int, Any> = persistentMapOf()
        private set

    private val hidden = LinkedHashSet<Int>()
    private val positionKeys = HashMap<Int, Any>()
    private val states = HashMap<Any, KeyState>()

    /** Held positions that are not published. */
    val hiddenPositions: Set<Int> get() = hidden

    /**
     * Merges [incoming] with the same semantics as [mergeIntoCache] and resolves key conflicts.
     *
     * @param confirmOnRewrite Whether putting a key back on a position where it is hidden confirms
     * the conflict.
     */
    fun write(
        incoming: Map<Int, T>,
        cacheRange: IntRange,
        pruneExisting: Boolean,
        confirmOnRewrite: Boolean,
    ): WriteResult {
        val stale = if (pruneExisting) values.keys.filter { it !in cacheRange } else emptyList()
        if (stale.isEmpty() && incoming.isEmpty()) return EMPTY_RESULT

        val tx = Transaction()
        for (position in stale) tx.remove(position)
        for ((position, value) in incoming) {
            if (position in cacheRange) tx.put(position, value, confirmOnRewrite)
        }
        return tx.commit()
    }

    /** Drops every held position outside [range]. */
    fun prune(range: IntRange): WriteResult =
        write(incoming = emptyMap(), cacheRange = range, pruneExisting = true, confirmOnRewrite = false)

    fun clear() {
        values = persistentMapOf()
        visible = persistentMapOf()
        keys = persistentMapOf()
        hidden.clear()
        positionKeys.clear()
        states.clear()
    }

    /** Confirms the conflict hiding [position]; returns `null` when [position] is not hidden. */
    fun confirm(position: Int): ConfirmedConflict? {
        if (position !in hidden) return null
        val key = positionKeys.getValue(position)
        val tx = Transaction()
        tx.confirm(key)
        tx.commit()
        return ConfirmedConflict(key, states.getValue(key).positions.toSet())
    }

    /** Keys with hidden positions, each mapped to all of its positions. */
    fun unconfirmedConflicts(): Map<Any, Set<Int>> {
        val result = LinkedHashMap<Any, Set<Int>>()
        for (position in hidden) {
            val key = positionKeys.getValue(position)
            if (key !in result) result[key] = states.getValue(key).positions.toSet()
        }
        return result
    }

    /** Batches changes through builders and republishes only the positions of affected keys. */
    private inner class Transaction {
        private val valuesBuilder = values.builder()
        private val visibleBuilder = visible.builder()
        private val keysBuilder = keys.builder()
        private val affectedKeys = LinkedHashSet<Any>()
        private val confirmedKeys = LinkedHashSet<Any>()
        private val rekeyed = HashSet<Int>()

        fun remove(position: Int) {
            val key = positionKeys.remove(position) ?: return
            release(position, key)
            valuesBuilder.remove(position)
            visibleBuilder.remove(position)
            keysBuilder.remove(position)
            hidden.remove(position)
            rekeyed.remove(position)
        }

        fun put(position: Int, value: T, confirmOnRewrite: Boolean) {
            val key = itemKey(value)
            val oldKey = positionKeys[position]
            valuesBuilder[position] = value
            if (oldKey == key) {
                rewrite(position, key, confirmOnRewrite)
                return
            }
            if (oldKey != null) release(position, oldKey)
            positionKeys[position] = key
            rekeyed += position
            val state = states.getOrPut(key) { KeyState(position) }
            state.positions += position
            state.owner = position
            state.confirmed = false
            affectedKeys += key
        }

        fun confirm(key: Any) {
            states.getValue(key).confirmed = true
            affectedKeys += key
        }

        private fun rewrite(position: Int, key: Any, confirmOnRewrite: Boolean) {
            val state = states.getValue(key)
            state.positions.remove(position)
            state.positions += position
            if (confirmOnRewrite && position in hidden) {
                state.confirmed = true
                confirmedKeys += key
            }
            affectedKeys += key
        }

        private fun release(position: Int, key: Any) {
            val state = states.getValue(key)
            state.positions.remove(position)
            if (state.positions.isEmpty()) {
                states.remove(key)
                return
            }
            if (state.owner == position) state.owner = state.positions.last()
            if (state.positions.size == 1) state.confirmed = false
            affectedKeys += key
        }

        fun commit(): WriteResult {
            val newlyHidden = LinkedHashSet<Int>()
            for (key in affectedKeys) {
                val state = states[key] ?: continue
                for (position in state.positions) {
                    val wasHidden = position in hidden && position !in rekeyed
                    if (publish(position, key, state) && !wasHidden) newlyHidden += position
                }
            }
            values = valuesBuilder.build()
            visible = visibleBuilder.build()
            keys = keysBuilder.build()

            val confirmed = confirmedKeys.mapNotNull { key ->
                val state = states[key]
                if (state != null && state.confirmed && state.positions.size > 1) {
                    ConfirmedConflict(key, state.positions.toSet())
                } else {
                    null
                }
            }
            return if (confirmed.isEmpty() && newlyHidden.isEmpty()) {
                EMPTY_RESULT
            } else {
                WriteResult(confirmed, newlyHidden)
            }
        }

        /** Applies the visibility rule to [position]; returns whether it ends up hidden. */
        private fun publish(position: Int, key: Any, state: KeyState): Boolean {
            if (position == state.owner || state.confirmed) {
                visibleBuilder[position] = valuesBuilder.getValue(position)
                if (position == state.owner) keysBuilder[position] = key else keysBuilder.remove(position)
                hidden.remove(position)
                return false
            }
            visibleBuilder.remove(position)
            keysBuilder.remove(position)
            hidden += position
            return true
        }
    }

    private companion object {
        val EMPTY_RESULT = WriteResult(emptyList(), emptySet())
    }
}

/** A key that is confirmed to sit on several [positions]; all of them are visible. */
internal data class ConfirmedConflict(val key: Any, val positions: Set<Int>)

/**
 * Outcome of a [KeyedCache] write.
 *
 * @property confirmed Conflicts confirmed by this write.
 * @property newlyHidden Positions that were visible or newly written before the write and are hidden
 * after it.
 */
internal data class WriteResult(val confirmed: List<ConfirmedConflict>, val newlyHidden: Set<Int>)
