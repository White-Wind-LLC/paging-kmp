package ua.wwind.paging.sample.presentation.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import ua.wwind.paging.core.DataPortion
import ua.wwind.paging.core.Pager
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.sample.domain.model.User
import ua.wwind.paging.sample.domain.repository.UserRepository

/**
 * ViewModel for managing user list with paging functionality
 */
class UserListViewModel(
    private val userRepository: UserRepository,
    scope: CoroutineScope,
) {
    // Pager configuration optimized for user lists
    private val pager = Pager<User>(
        loadSize = 20,          // Load 20 users per request
        preloadSize = 60,       // Preload 60 users around current position
        cacheSize = 100,        // Keep 100 users in memory
        scope = scope,
        readData = ::loadUsers
    )

    /**
     * Flow of paging data containing users and load states
     */
    val pagingFlow: Flow<PagingData<User>> = pager.flow

    /**
     * Load users from repository and convert to DataPortion format
     * This function is called by the Pager automatically
     */
    private suspend fun loadUsers(position: Int, loadSize: Int): DataPortion<User> {
        val offset = position - 1 // Convert from 1-based to 0-based indexing
        val userPage = userRepository.getUsers(offset, loadSize)

        // Create position-to-user mapping for the pager
        val userMap = userPage.users.mapIndexed { index, user ->
            (position + index) to user
        }.toMap()

        return DataPortion(
            totalSize = userPage.totalCount,
            values = userMap
        )
    }
}