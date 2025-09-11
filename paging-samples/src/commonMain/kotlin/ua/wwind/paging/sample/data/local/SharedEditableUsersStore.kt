package ua.wwind.paging.sample.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import ua.wwind.paging.sample.domain.model.User
import ua.wwind.paging.sample.domain.model.UserRole

/**
 * In-memory editable store with a backing MutableList and a StateFlow that emits on any change.
 * Positions are 1-based.
 */
class SharedEditableUsersStore(initialSize: Int = 200) {
    private val users: MutableList<User> = MutableList(initialSize) { index ->
        val id = index + 1
        User(
            id = id,
            firstName = "User$" + id,
            lastName = "Sample",
            email = "user$id@example.com",
            role = UserRole.entries[id % UserRole.entries.size],
            avatarUrl = "https://api.dicebear.com/7.x/avataaars/svg?seed=$id",
            isActive = id % 2 == 0,
            joinedDate = "2023-01-${(id % 28 + 1).toString().padStart(2, '0')}"
        )
    }

    private val usersFlow: MutableStateFlow<List<User>> = MutableStateFlow(users.toList())

    fun totalFlow(): Flow<Int> = usersFlow.map { it.size }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun portionMapFlow(startPosition: Int, size: Int): Flow<Map<Int, User>> =
        usersFlow
            .mapLatest { list ->
                val total = list.size
                val startIndex = (startPosition - 1).coerceAtLeast(0)
                val endIndexExclusive = (startIndex + size).coerceAtMost(total)
                if (startIndex in 0 until total) list.subList(startIndex, endIndexExclusive) else emptyList()
            }.distinctUntilChanged()
            .map { slice ->
                slice.mapIndexed { idx, user -> (startPosition + idx) to user }.toMap()
            }

    fun updateAt(position: Int, transform: (User) -> User) {
        val index = position - 1
        if (index in users.indices) {
            users[index] = transform(users[index])
            usersFlow.value = users.toList()
        }
    }

    fun addAt(position: Int, user: User) {
        val index = (position - 1).coerceIn(0, users.size)
        users.add(index, user)
        usersFlow.value = users.toList()
    }

    fun removeAt(position: Int) {
        val index = position - 1
        if (index in users.indices) {
            users.removeAt(index)
            usersFlow.value = users.toList()
        }
    }

    fun totalSize(): Int = users.size

    fun userAt(position: Int): User? = users.getOrNull(position - 1)
}
