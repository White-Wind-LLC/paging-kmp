import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import ua.wwind.paging.sample.presentation.screen.MainTabsScreen

/**
 * Main application composable
 * Sets up dependency injection and theme
 */
@Composable
fun App() {
    MaterialTheme {
        val coroutineScope = rememberCoroutineScope()
        MainTabsScreen(scope = coroutineScope)
    }
}