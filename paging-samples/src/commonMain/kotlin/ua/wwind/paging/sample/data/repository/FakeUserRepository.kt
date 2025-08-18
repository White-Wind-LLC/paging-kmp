package ua.wwind.paging.sample.data.repository

import kotlinx.coroutines.delay
import ua.wwind.paging.sample.domain.model.User
import ua.wwind.paging.sample.domain.model.UserRole
import ua.wwind.paging.sample.domain.repository.UserPage
import ua.wwind.paging.sample.domain.repository.UserRepository
import kotlin.random.Random

/**
 * Fake implementation of UserRepository for demonstration purposes
 * Simulates network delays and generates realistic user data
 */
class FakeUserRepository : UserRepository {

    companion object {
        private const val TOTAL_USERS = 2500
        private const val MIN_NETWORK_DELAY_MS = 1000L
        private const val MAX_NETWORK_DELAY_MS = 2000L

        private val firstNames = listOf(
            "John", "Jane", "Alice", "Bob", "Charlie", "Diana", "Edward", "Fiona",
            "George", "Helen", "Ivan", "Julia", "Kevin", "Laura", "Mike", "Nancy",
            "Oliver", "Patricia", "Quinn", "Rachel", "Steve", "Teresa", "Ulrich",
            "Victoria", "William", "Xenia", "Yolanda", "Zachary", "Amanda", "Brian"
        )

        private val lastNames = listOf(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
            "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
            "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
            "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
            "Ramirez", "Lewis", "Robinson", "Walker", "Young", "Allen", "King"
        )

        private val domains = listOf("gmail.com", "yahoo.com", "outlook.com", "company.com")
        private val roles = UserRole.entries.toTypedArray()
    }

    override suspend fun getUsers(offset: Int, limit: Int): UserPage {
        // Simulate longer network delay for better demonstration
        delay(Random.nextLong(MIN_NETWORK_DELAY_MS, MAX_NETWORK_DELAY_MS))

        // Simulate potential errors (5% chance)
        if (Random.nextFloat() < 0.05f) {
            throw Exception("Network error: Failed to fetch users")
        }

        val users = generateUsers(offset, limit)
        return UserPage(users, TOTAL_USERS)
    }


    private fun generateUsers(offset: Int, limit: Int): List<User> {
        return (1..limit).map { index ->
            val id = offset + index
            val firstName = firstNames[id % firstNames.size]
            val lastName = lastNames[(id / firstNames.size) % lastNames.size]
            val email = "${firstName.lowercase()}.${lastName.lowercase()}$id@${domains[id % domains.size]}"
            val role = roles[id % roles.size]
            val avatarUrl = "https://api.dicebear.com/7.x/avataaars/svg?seed=$id"
            val isActive = Random.nextBoolean()
            val joinedDate = generateJoinDate(id)

            User(
                id = id,
                firstName = firstName,
                lastName = lastName,
                email = email,
                role = role,
                avatarUrl = avatarUrl,
                isActive = isActive,
                joinedDate = joinedDate
            )
        }
    }

    private fun generateJoinDate(seed: Int): String {
        val random = Random(seed)
        val year = 2020 + random.nextInt(5) // 2020-2024
        val month = 1 + random.nextInt(12)
        val day = 1 + random.nextInt(28)
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }
}