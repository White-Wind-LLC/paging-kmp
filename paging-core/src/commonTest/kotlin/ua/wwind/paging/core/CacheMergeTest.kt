package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlin.test.Test

/**
 * Contract of the single-pass cache merge used by both `Pager` and `StreamingPager`.
 *
 * The merge must produce exactly what the previous
 * `(current + incoming).filterKeys { it in cacheRange }.toPersistentMap()` expression produced,
 * while touching only the delta instead of rebuilding the whole cache.
 */
class CacheMergeTest {

    private fun cache(vararg keys: Int) = keys.associateWith { "v$it" }.toPersistentMap()

    @Test
    fun writes_incoming_values_that_fall_inside_the_cache_range() {
        val merged = cache(1, 2).mergeIntoCache(mapOf(3 to "v3", 1 to "updated"), 0..10)

        merged shouldBe mapOf(1 to "updated", 2 to "v2", 3 to "v3")
    }

    @Test
    fun drops_incoming_values_that_fall_outside_the_cache_range() {
        val merged = cache(1).mergeIntoCache(mapOf(2 to "v2", 99 to "v99"), 0..10)

        merged shouldBe mapOf(1 to "v1", 2 to "v2")
    }

    @Test
    fun prunes_existing_keys_that_left_the_cache_range() {
        val merged = cache(1, 5, 99).mergeIntoCache(mapOf(6 to "v6"), 0..10)

        merged shouldBe mapOf(1 to "v1", 5 to "v5", 6 to "v6")
    }

    @Test
    fun keeps_existing_out_of_range_keys_when_pruning_is_skipped() {
        val merged = cache(1, 99).mergeIntoCache(mapOf(2 to "v2"), 0..10, pruneExisting = false)

        merged shouldBe mapOf(1 to "v1", 2 to "v2", 99 to "v99")
    }

    @Test
    fun returns_the_same_instance_when_there_is_nothing_to_do() {
        val current = cache(1, 2)

        val merged = current.mergeIntoCache(emptyMap(), 0..10)

        (merged === current) shouldBe true
    }

    @Test
    fun merging_into_an_empty_cache_yields_the_incoming_values_in_range() {
        val merged = persistentMapOf<Int, String>().mergeIntoCache(mapOf(1 to "v1", 42 to "v42"), 0..10)

        merged shouldBe mapOf(1 to "v1")
    }

    @Test
    fun prune_to_range_removes_only_out_of_range_keys() {
        val pruned = cache(0, 3, 11, 50).pruneToRange(0..10)

        pruned shouldBe mapOf(0 to "v0", 3 to "v3")
    }

    @Test
    fun prune_to_range_returns_the_same_instance_when_everything_is_in_range() {
        val current = cache(1, 2, 3)

        (current.pruneToRange(0..10) === current) shouldBe true
    }
}
