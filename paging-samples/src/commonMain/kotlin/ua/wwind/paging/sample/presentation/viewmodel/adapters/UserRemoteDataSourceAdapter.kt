package ua.wwind.paging.sample.presentation.viewmodel.adapters

import kotlinx.collections.immutable.toPersistentMap
import ua.wwind.paging.core.DataPortion
import ua.wwind.paging.core.RemoteDataSource
import ua.wwind.paging.sample.domain.model.User
import ua.wwind.paging.sample.domain.repository.UserRemoteDataSource

/**
 * Adapter to bridge [UserRemoteDataSource] into the core [RemoteDataSource] interface.
 */
class UserRemoteDataSourceAdapter(
    private val remote: UserRemoteDataSource,
) : RemoteDataSource<User, Unit> {

    override suspend fun fetch(startPosition: Int, size: Int, query: Unit): DataPortion<User> {
        val page = remote.getUsers(startPosition, size)
        val values = page.users.mapIndexed { index, user ->
            (startPosition + index) to user
        }
            .toMap()
            .toPersistentMap()
        return DataPortion(totalSize = page.totalCount, values = values)
    }
}
