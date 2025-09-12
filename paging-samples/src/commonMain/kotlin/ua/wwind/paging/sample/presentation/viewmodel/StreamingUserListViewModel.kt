package ua.wwind.paging.sample.presentation.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import ua.wwind.paging.core.ExperimentalStreamingPagerApi
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.core.StreamingPager
import ua.wwind.paging.core.StreamingPagerConfig
import ua.wwind.paging.sample.data.local.SharedEditableUsersStore
import ua.wwind.paging.sample.domain.model.User

@OptIn(ExperimentalStreamingPagerApi::class)
class StreamingUserListViewModel(
    private val store: SharedEditableUsersStore,
    scope: CoroutineScope,
    loadSize: Int = 20,
    preloadSize: Int = 60,
    cacheSize: Int = 100,
) {
    private val pager = StreamingPager<User>(
        config = StreamingPagerConfig(
            loadSize = loadSize,
            preloadSize = preloadSize,
            cacheSize = cacheSize,
        ),
        scope = scope,
        readTotal = { store.totalFlow() },
        readPortion = { start, size -> store.portionMapFlow(start, size) }
    )

    val pagingFlow: Flow<PagingData<User>> = pager.flow

    // Editing API
    fun updateUser(position: Int, transform: (User) -> User) = store.updateAt(position, transform)
    fun addUserAt(position: Int, user: User) = store.addAt(position, user)
    fun removeUserAt(position: Int) = store.removeAt(position)
    fun totalSize(): Int = store.totalSize()
    fun userAt(position: Int): User? = store.userAt(position)
}
