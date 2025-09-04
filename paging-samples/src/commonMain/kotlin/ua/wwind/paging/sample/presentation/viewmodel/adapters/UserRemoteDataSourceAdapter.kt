package ua.wwind.paging.sample.presentation.viewmodel.adapters

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
        val offset = startPosition - 1
        val page = remote.getUsers(offset, size)
        val values = page.users.mapIndexed { index, user ->
            (startPosition + index) to user
        }.toMap()
        return DataPortion(totalSize = page.totalCount, values = values)
    }
}
