package ua.wwind.paging.core.stream

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Unit tests for the chunk planner behind `StreamingPager`.
 *
 * The invariant that matters for #5: `planStreamWindow` must never hand back an empty chunk. An
 * empty chunk is marked `Loading` by `openMissingStreamsLocked` but skipped by `openStream`, so the
 * marker would never be cleared and the pager would report `Loading` forever.
 */
class WindowHelpersTest {

    private val config = StreamingPagerConfig(
        loadSize = 20,
        preloadSize = 20,
        cacheSize = 200,
        closeThreshold = 20,
        keyDebounceMs = 0,
    )

    @Test
    fun plan_never_returns_an_empty_chunk_for_an_out_of_bounds_key() {
        val totalSize = 40
        // The key exactly on the boundary is the #5 repro; the others are the same class of bug.
        val keys = listOf(-100, -1, 0, 39, 40, 41, 1_000)

        keys.forEach { key ->
            val plan = planStreamWindow(
                activeRanges = setOf(0..19, 20..39),
                key = key,
                totalSize = totalSize,
                config = config,
            )
            plan.chunks.any { it.isEmpty() } shouldBe false
        }
    }

    @Test
    fun plan_keeps_every_chunk_inside_the_total() {
        val totalSize = 47
        listOf(-5, 0, 23, 46, 47, 90).forEach { key ->
            val plan = planStreamWindow(
                activeRanges = emptySet(),
                key = key,
                totalSize = totalSize,
                config = config,
            )
            plan.chunks.all { it.first >= 0 && it.last < totalSize } shouldBe true
        }
    }

    @Test
    fun plan_for_a_key_on_the_boundary_matches_the_plan_for_the_last_valid_index() {
        val totalSize = 40
        val active = setOf(0..19, 20..39)

        val onBoundary = planStreamWindow(active, key = totalSize, totalSize = totalSize, config = config)
        val lastValid = planStreamWindow(active, key = totalSize - 1, totalSize = totalSize, config = config)

        onBoundary shouldBe lastValid
        onBoundary.chunks shouldBe listOf(0..19, 20..39)
    }

    @Test
    fun plan_with_unknown_total_still_bootstraps_the_first_chunk() {
        val plan = planStreamWindow(emptySet(), key = 0, totalSize = 0, config = config)

        plan.window shouldBe null
        plan.chunks shouldBe listOf(0..<config.loadSize)
    }
}
