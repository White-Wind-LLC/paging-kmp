package ua.wwind.paging.core

import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PagingDataTest {

    @Test
    fun empty_factory_returns_success_and_empty_map_and_noop_retry() {
        val empty: PagingData<Int> = PagingData.empty()

        assertTrue(empty.data.isEmpty())
        assertIs<LoadState.Success>(empty.loadState)

        // no-op retry should not throw
        empty.retry(123)

        // mapping empty keeps it empty and preserves success state
        val mapped = empty.map { it.toString() }
        assertTrue(mapped.data.isEmpty())
        assertIs<LoadState.Success>(mapped.loadState)
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
        assertEquals(100, mapped.data.size)
        assertIs<LoadState.Success>(mapped.loadState)
        // retry function reference preserved
        assertSame(mapped.retry, retryFn)

        // transformed values for loaded entries
        val entry2 = mapped.data[2]
        assertIs<EntryState.Success<String>>(entry2)
        assertEquals("v=20", entry2.value)

        val entry5 = mapped.data[5]
        assertIs<EntryState.Success<String>>(entry5)
        assertEquals("v=50", entry5.value)

        // onGet callback preserved and invoked via access
        assertEquals(2, getCount)
        assertEquals(5, lastGetKey)

        // retry still callable
        mapped.retry(77)
        assertEquals(77, lastRetriedKey)
    }
}
