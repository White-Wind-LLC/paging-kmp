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
        map.keyAt(20) shouldNotBe 20
        (0 until 30).map { map.keyAt(it) }.toSet().size shouldBe 30
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
