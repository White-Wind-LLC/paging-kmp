package ua.wwind.paging.sample.domain.model

/**
 * Domain model representing a user in the application
 */
data class User(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val email: String,
    val role: UserRole,
    val avatarUrl: String,
    val isActive: Boolean,
    val joinedDate: String,
) {
    val fullName: String
        get() = "$firstName $lastName"
}

enum class UserRole {
    ADMIN,
    MODERATOR,
    USER,
    GUEST
}