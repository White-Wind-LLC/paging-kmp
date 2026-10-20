package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test

class PagingMapKeysTest {

    private val fallback = PagingMap.FALLBACK_KEY_PREFIX

    @Test
    fun keyAt_returnsStoredKey_orFallback() {
        val map = PagingMap<Int>(3, persistentMapOf(), {}, persistentMapOf(0 to "a"))

        map.keyAt(0) shouldBe "a"
        map.keyAt(1) shouldBe "ua.wwind.paging#1"
        map.keyAt(99) shouldBe "ua.wwind.paging#99"
    }

    @Test
    fun keyAt_doesNotCallOnGet() {
        var calls = 0
        val map = PagingMap<Int>(250, persistentMapOf(), { calls++ })

        for (i in 0..249) map.keyAt(i)

        calls shouldBe 0
    }

    @Test
    fun fallback_neverEqualsIntKey() {
        val map = PagingMap<Int>(30, persistentMapOf(), {}, persistentMapOf(5 to 20))

        map.keyAt(5) shouldBe 20
        map.keyAt(20) shouldBe "${fallback}20"
        (0 until 30).map { map.keyAt(it) }.toSet().size shouldBe 30
    }

    @Test
    fun copy_keepsKeysOfUnchangedPositionsOnly() {
        val map = PagingMap(
            size = 4,
            values = persistentMapOf(0 to 1, 1 to 2, 3 to 4),
            onGet = {},
            keys = persistentMapOf(0 to "a", 1 to "b", 3 to "d"),
        )

        val copy = map.copy(size = 3, values = persistentMapOf(0 to 1, 1 to 9))

        copy.keyAt(0) shouldBe "a"
        copy.keyAt(1) shouldBe "${fallback}1"
        copy.keyAt(3) shouldBe "${fallback}3"
    }

    @Test
    fun copy_withSameValues_keepsAllKeys() {
        var calls = 0
        val map = PagingMap(2, persistentMapOf(0 to 1), {}, persistentMapOf(0 to "a"))

        val copy = map.copy(onGet = { calls++ })

        copy.keyAt(0) shouldBe "a"
        copy[0]
        calls shouldBe 1
        copy shouldNotBe map
    }

    @Test
    fun equality_includesKeys() {
        val onGet: (Int) -> Unit = {}
        val values = persistentMapOf(0 to 1)
        val keyed = PagingMap(1, values, onGet, persistentMapOf(0 to "a"))

        keyed shouldBe PagingMap(1, values, onGet, persistentMapOf(0 to "a"))
        keyed shouldNotBe PagingMap(1, values, onGet)
    }

    @Test
    fun mapValues_keepsKeysAndHiddenPositions() {
        val map = PagingMap(2, persistentMapOf(0 to 1), {}, persistentMapOf(0 to "a"))

        val mapped = map.mapValues { it * 10 }

        mapped.values shouldBe persistentMapOf(0 to 10)
        mapped.keyAt(0) shouldBe "a"
        mapped.keyAt(1) shouldBe "${fallback}1"
    }

    @Test
    fun pagingDataMap_keepsKeys() {
        val map = PagingMap(2, persistentMapOf(0 to 1), {}, persistentMapOf(0 to "a"))

        PagingData(map, LoadState.Success) {}.map { it * 10 }.data.keyAt(0) shouldBe "a"
    }

    @Test
    fun legacyConstructor_hasOnlyFallbacks() {
        PagingMap(2, persistentMapOf(0 to 1)) {}.keyAt(0) shouldBe "${fallback}0"
    }
}
