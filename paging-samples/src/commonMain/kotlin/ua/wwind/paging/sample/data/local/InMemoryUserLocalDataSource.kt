package ua.wwind.paging.sample.data.local

import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ua.wwind.paging.core.DataPortion
import ua.wwind.paging.core.LocalDataSource
import ua.wwind.paging.sample.domain.model.User

/**
 * Simple in-memory implementation of [LocalDataSource] for users backed by [MutableStateFlow].
 */
class InMemoryUserLocalDataSource : LocalDataSource<User, Unit> {
    private val positionToUser = MutableStateFlow<Map<Int, User>>(emptyMap())
    private val totalSizeFlow = MutableStateFlow(0)
    private val _cachedCount = MutableStateFlow(0)
    val cachedCount: StateFlow<Int> = _cachedCount
    private val _lastSavedMinKey = MutableStateFlow<Int?>(null)
    val lastSavedMinKey: StateFlow<Int?> = _lastSavedMinKey

    override suspend fun read(startPosition: Int, size: Int, query: Unit): DataPortion<User> {
        val end = startPosition + size - 1
        val snapshot = positionToUser.value
        val values = (startPosition..end)
            .asSequence()
            .mapNotNull { pos -> snapshot[pos]?.let { pos to it } }
            .toMap()
            .toPersistentMap()
        return DataPortion(totalSize = totalSizeFlow.value, values = values)
    }

    override suspend fun save(portion: DataPortion<User>) {
        if (portion.totalSize > 0) totalSizeFlow.value = portion.totalSize
        positionToUser.value = positionToUser.value + portion.values
        _cachedCount.value = positionToUser.value.size
        val minKey = portion.values.keys.minOrNull()
        if (minKey != null) _lastSavedMinKey.value = minKey
    }

    override suspend fun clear() {
        totalSizeFlow.value = 0
        positionToUser.value = emptyMap()
        _cachedCount.value = 0
        _lastSavedMinKey.value = null
    }
}
