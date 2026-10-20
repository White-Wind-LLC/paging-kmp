package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class KeyedCacheTest {

    private val range = 0..99

    private fun cache() = KeyedCache<Pair<String, Int>> { it.first }

    private fun KeyedCache<Pair<String, Int>>.put(
        vararg entries: Pair<Int, String>,
        confirmOnRewrite: Boolean = false,
    ): WriteResult = write(
        incoming = entries.associate { (position, id) -> position to (id to 1) },
        cacheRange = range,
        pruneExisting = false,
        confirmOnRewrite = confirmOnRewrite,
    )

    private fun conflictOn20(): KeyedCache<Pair<String, Int>> = cache().apply {
        put(20 to "u")
        put(19 to "u")
    }

    @Test
    fun uniqueKeys_allVisible() {
        val cache = cache()

        cache.put(0 to "a", 1 to "b")

        cache.visible.keys shouldBe setOf(0, 1)
        cache.keys shouldBe mapOf(0 to "a", 1 to "b")
        cache.hiddenPositions shouldBe emptySet()
    }

    @Test
    fun latestWriteWins_olderHidden() {
        val cache = cache()
        cache.put(20 to "u")

        val result = cache.put(19 to "u")

        cache.keys[19] shouldBe "u"
        (20 in cache.visible) shouldBe false
        (20 in cache.keys) shouldBe false
        cache.hiddenPositions shouldBe setOf(20)
        cache.values[20] shouldBe ("u" to 1)
        result.newlyHidden shouldBe setOf(20)
    }

    @Test
    fun winnerOverwritten_loserRestored() {
        val cache = conflictOn20()

        val result = cache.put(19 to "x")

        cache.keys shouldBe mapOf(19 to "x", 20 to "u")
        cache.hiddenPositions shouldBe emptySet()
        cache.visible.keys shouldBe setOf(19, 20)
        result.newlyHidden shouldBe emptySet()
    }

    @Test
    fun winnerPruned_loserRestored() {
        val cache = conflictOn20()

        cache.prune(20..99)

        cache.keys[20] shouldBe "u"
        (20 in cache.visible) shouldBe true
        (19 in cache.values) shouldBe false
        cache.hiddenPositions shouldBe emptySet()
    }

    @Test
    fun rewriteHidden_confirms_whenAllowed() {
        val cache = conflictOn20()

        val result = cache.put(20 to "u", confirmOnRewrite = true)

        result.confirmed shouldBe listOf(ConfirmedConflict("u", setOf(19, 20)))
        cache.visible.keys shouldBe setOf(19, 20)
        cache.keys[19] shouldBe "u"
        (20 in cache.keys) shouldBe false
        cache.hiddenPositions shouldBe emptySet()
    }

    @Test
    fun rewriteHidden_doesNotConfirm_whenDisallowed() {
        val cache = conflictOn20()

        val result = cache.put(20 to "u", confirmOnRewrite = false)

        result.confirmed shouldBe emptyList()
        result.newlyHidden shouldBe emptySet()
        cache.hiddenPositions shouldBe setOf(20)
    }

    @Test
    fun confirmedConflict_endsWhenOnePositionLeaves() {
        val cache = conflictOn20()
        cache.put(20 to "u", confirmOnRewrite = true)

        cache.put(20 to "v")

        cache.keys shouldBe mapOf(19 to "u", 20 to "v")

        val result = cache.put(21 to "u")

        cache.keys[21] shouldBe "u"
        cache.hiddenPositions shouldBe setOf(19)
        result.newlyHidden shouldBe setOf(19)
        result.confirmed shouldBe emptyList()
    }

    @Test
    fun joiningConfirmedKey_startsFreshConflict() {
        val cache = conflictOn20()
        cache.put(20 to "u", confirmOnRewrite = true)

        cache.put(30 to "u")

        cache.keys[30] shouldBe "u"
        cache.hiddenPositions shouldBe setOf(19, 20)
    }

    @Test
    fun releasedOwner_passesToMostRecentlyWritten() {
        val cache = cache()
        cache.put(10 to "u")
        cache.put(30 to "u")
        cache.put(20 to "u")

        cache.put(20 to "x")

        cache.keys[30] shouldBe "u"
        cache.hiddenPositions shouldBe setOf(10)
    }

    @Test
    fun withinOnePortion_laterPositionWins() {
        val cache = cache()

        val result = cache.put(19 to "u", 20 to "u")

        cache.keys[20] shouldBe "u"
        cache.hiddenPositions shouldBe setOf(19)
        result.newlyHidden shouldBe setOf(19)
    }

    @Test
    fun confirm_byPosition() {
        val cache = conflictOn20()

        cache.confirm(19) shouldBe null
        cache.confirm(20) shouldBe ConfirmedConflict("u", setOf(19, 20))
        cache.hiddenPositions shouldBe emptySet()
        cache.visible.keys shouldBe setOf(19, 20)
        cache.confirm(20) shouldBe null
    }

    @Test
    fun unconfirmedConflicts_and_confirmAll() {
        val cache = cache()
        cache.put(1 to "a", 5 to "b")
        cache.put(2 to "a", 6 to "b", 7 to "b")

        cache.unconfirmedConflicts() shouldBe mapOf("a" to setOf(1, 2), "b" to setOf(5, 6, 7))

        val confirmed = cache.confirmAll()

        confirmed.toSet() shouldBe setOf(
            ConfirmedConflict("a", setOf(1, 2)),
            ConfirmedConflict("b", setOf(5, 6, 7)),
        )
        cache.hiddenPositions shouldBe emptySet()
        cache.unconfirmedConflicts() shouldBe emptyMap()
        cache.visible.keys shouldBe setOf(1, 2, 5, 6, 7)
    }

    @Test
    fun outsideCacheRange_ignored_and_pruned() {
        val cache = cache()

        cache.write(mapOf(150 to ("z" to 1)), range, pruneExisting = false, confirmOnRewrite = false)

        cache.values shouldBe emptyMap()
        cache.keys shouldBe emptyMap()

        cache.put(0 to "a", 10 to "b")
        cache.write(emptyMap(), 10..99, pruneExisting = true, confirmOnRewrite = false)

        cache.values.keys shouldBe setOf(10)
        cache.visible.keys shouldBe setOf(10)
        cache.keys shouldBe mapOf(10 to "b")
    }

    @Test
    fun clear_resetsEverything() {
        val cache = conflictOn20()

        cache.clear()

        cache.values shouldBe emptyMap()
        cache.visible shouldBe emptyMap()
        cache.keys shouldBe emptyMap()
        cache.hiddenPositions shouldBe emptySet()
        cache.unconfirmedConflicts() shouldBe emptyMap()

        cache.put(5 to "u")
        cache.keys shouldBe mapOf(5 to "u")
    }

    @Test
    fun intKeys_sameAsPositions() {
        val cache = KeyedCache<Pair<String, Int>> { it.second }

        cache.write(mapOf(5 to ("a" to 20)), range, pruneExisting = false, confirmOnRewrite = false)

        cache.keys shouldBe mapOf<Int, Any>(5 to 20)
        (20 in cache.values) shouldBe false
        cache.hiddenPositions shouldBe emptySet()
    }
}
