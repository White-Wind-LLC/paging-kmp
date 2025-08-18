package ua.wwind.paging.sample.domain.repository

import ua.wwind.paging.sample.domain.model.User

/**
 * Repository interface for accessing user data
 */
interface UserRepository {
    /**
     * Get users with pagination support
     * @param offset Starting position (0-based)
     * @param limit Number of users to fetch
     * @return List of users and total count
     */
    suspend fun getUsers(offset: Int, limit: Int): UserPage
}

/**
 * Container for paginated user data
 */
data class UserPage(
    val users: List<User>,
    val totalCount: Int,
)