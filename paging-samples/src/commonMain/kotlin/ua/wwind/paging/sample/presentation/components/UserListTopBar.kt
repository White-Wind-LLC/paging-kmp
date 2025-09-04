package ua.wwind.paging.sample.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.wwind.paging.core.LoadState

/**
 * Top bar component showing app title and loading status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListTopBar(
    totalUsers: Int,
    loadState: LoadState,
    useMediator: Boolean,
    onUseMediatorChange: (Boolean) -> Unit,
    cachedCount: Int?,
    lastMinSavedKey: Int?,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Paging Sample",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val summary = buildString {
                        append(
                            if (totalUsers > 0) "$totalUsers users" else "Loading users..."
                        )
                        if (useMediator && cachedCount != null) {
                            append("  •  cache: ")
                            append(cachedCount)
                        }
                        if (useMediator && lastMinSavedKey != null) {
                            append("  •  minKey: ")
                            append(lastMinSavedKey)
                        }
                    }
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (loadState == LoadState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        },
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mediator",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = useMediator,
                    onCheckedChange = onUseMediatorChange
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    )
}