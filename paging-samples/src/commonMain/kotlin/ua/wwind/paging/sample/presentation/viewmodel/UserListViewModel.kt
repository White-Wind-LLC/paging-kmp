package ua.wwind.paging.sample.presentation.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ua.wwind.paging.core.DataPortion
import ua.wwind.paging.core.LocalDataSource
import ua.wwind.paging.core.Pager
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.core.PagingMediator
import ua.wwind.paging.sample.domain.model.User
import ua.wwind.paging.sample.domain.repository.UserRemoteDataSource
import ua.wwind.paging.sample.presentation.viewmodel.adapters.UserRemoteDataSourceAdapter

/**
 * ViewModel for managing user list with paging functionality.
 * Supports two modes:
 * - Direct: read from remote source without local cache.
 * - Mediator: coordinate remote with a local cache via PagingMediator.
 */
class UserListViewModel(
    private val remote: UserRemoteDataSource,
    private val local: LocalDataSource<User, Unit>,
    private val useMediator: Boolean,
    scope: CoroutineScope,
) {
    val pagingFlow: Flow<PagingData<User>> = if (useMediator) {
        val mediator = PagingMediator<User, Unit>(
            scope = scope,
            local = local,
            remote = UserRemoteDataSourceAdapter(remote)
        )
        mediator.flow(Unit)
    } else {
        val pager = Pager<User>(
            loadSize = 20,
            preloadSize = 60,
            cacheSize = 100,
            scope = scope,
            readData = ::loadUsersDirect
        )
        pager.flow
    }

    /**
     * Clears local cache when mediator mode is active. No-op otherwise.
     */
    suspend fun clearCache() {
        if (useMediator) local.clear()
    }

    private fun loadUsersDirect(position: Int, loadSize: Int): Flow<DataPortion<User>> = flow {
        val page = remote.getUsers(position, loadSize)
        val userMap = page.users.mapIndexed { index, user ->
            (position + index) to user
        }.toMap()
        emit(DataPortion(totalSize = page.totalCount, values = userMap))
    }
}