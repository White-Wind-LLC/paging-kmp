import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ua.wwind.paging.sample.data.repository.FakeUserRepository
import ua.wwind.paging.sample.presentation.screen.UserListScreen
import ua.wwind.paging.sample.presentation.viewmodel.UserListViewModel

/**
 * Main application composable
 * Sets up dependency injection and theme
 */
@Composable
fun App() {
    MaterialTheme {
        // Simple dependency injection setup
        val coroutineScope = rememberCoroutineScope()
        val userRepository = remember { FakeUserRepository() }
        val viewModel = remember {
            UserListViewModel(
                userRepository = userRepository,
                scope = coroutineScope
            )
        }

        // Main screen
        UserListScreen(viewModel = viewModel)
    }
}