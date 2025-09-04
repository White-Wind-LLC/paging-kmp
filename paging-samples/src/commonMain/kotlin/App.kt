import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import ua.wwind.paging.sample.data.local.InMemoryUserLocalDataSource
import ua.wwind.paging.sample.data.repository.FakeUserRemoteDataSource
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
        val useMediatorState: MutableState<Boolean> = remember { mutableStateOf(false) }
        val useMediator by rememberUpdatedState(useMediatorState.value)

        val remote = remember { FakeUserRemoteDataSource() }
        val local = remember { InMemoryUserLocalDataSource() }

        val viewModel = remember(useMediator) {
            UserListViewModel(
                remote = remote,
                local = local,
                useMediator = useMediator,
                scope = coroutineScope
            )
        }

        // Main screen
        UserListScreen(
            viewModel = viewModel,
            useMediator = useMediator,
            onToggleUseMediator = { useMediatorState.value = it },
            onRefresh = {
                coroutineScope.launch { viewModel.clearCache() }
            },
            cachedCountFlow = local.cachedCount,
            lastMinSavedKeyFlow = local.lastSavedMinKey
        )
    }
}