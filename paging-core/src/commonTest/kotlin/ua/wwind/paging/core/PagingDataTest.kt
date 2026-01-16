package ua.wwind.paging.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test

class PagingDataTest {

    @Test
    fun empty_factory_returns_success_and_empty_map_and_noop_retry() {
        val empty: PagingData<Int> = PagingData.empty()

        empty.data.isEmpty() shouldBe true
        empty.loadState.shouldBeInstanceOf<LoadState.Success>()

        // no-op retry should not throw
        empty.retry(123)

        // mapping empty keeps it empty and preserves success state
        val mapped = empty.map { it.toString() }
        mapped.data.isEmpty() shouldBe true
        mapped.loadState.shouldBeInstanceOf<LoadState.Success>()
    }

    @Test
    fun map_transforms_loaded_items_and_preserves_metadata_and_onGet() {
        var lastGetKey: Int? = null
        var getCount = 0
        val onGet: (Int) -> Unit = { key ->
            lastGetKey = key
            getCount += 1
        }

        // dataset size is larger than currently loaded sparse values
        val sourceMap = PagingMap(size = 100, values = persistentMapOf(2 to 20, 5 to 50), onGet = onGet)

        var lastRetriedKey: Int? = null
        val retryFn: (Int) -> Unit = { key -> lastRetriedKey = key }
        val pagingData = PagingData(data = sourceMap, loadState = LoadState.Success, retry = retryFn)

        val mapped: PagingData<String> = pagingData.map { value -> "v=$value" }

        // size and state preserved
        mapped.data.size shouldBe 100
        mapped.loadState.shouldBeInstanceOf<LoadState.Success>()
        // retry function reference preserved
        mapped.retry shouldBeSameInstanceAs retryFn

        // transformed values for loaded entries
        val entry2 = mapped.data[2].shouldBeInstanceOf<EntryState.Success<String>>()
        entry2.value shouldBe "v=20"

        val entry5 = mapped.data[5].shouldBeInstanceOf<EntryState.Success<String>>()
        entry5.value shouldBe "v=50"

        // onGet callback preserved and invoked via access
        getCount shouldBe 2
        lastGetKey shouldBe 5

        // retry still callable
        mapped.retry(77)
        lastRetriedKey shouldBe 77
    }
}
