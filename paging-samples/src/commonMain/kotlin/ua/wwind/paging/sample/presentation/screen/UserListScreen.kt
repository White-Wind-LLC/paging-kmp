package ua.wwind.paging.sample.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import ua.wwind.paging.core.EntryState
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.core.PagingData
import ua.wwind.paging.sample.domain.model.User
import ua.wwind.paging.sample.presentation.components.EmptyState
import ua.wwind.paging.sample.presentation.components.ErrorOverlay
import ua.wwind.paging.sample.presentation.components.LoadingItem
import ua.wwind.paging.sample.presentation.components.UserCard
import ua.wwind.paging.sample.presentation.components.UserListTopBar
import ua.wwind.paging.sample.presentation.viewmodel.UserListViewModel
import ua.wwind.paging.sample.scrollbar.VerticalScrollbar
import ua.wwind.paging.sample.scrollbar.rememberScrollbarAdapter

/**
 * Main screen displaying paginated list of users
 */
@Composable
fun UserListScreen(
    viewModel: UserListViewModel,
    useMediator: Boolean,
    onToggleUseMediator: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    cachedCountFlow: Flow<Int>? = null,
    lastMinSavedKeyFlow: Flow<Int?>? = null,
    modifier: Modifier = Modifier,
) {
    val pagingData by viewModel.pagingFlow.collectAsState(initial = null)
    val listState = rememberLazyListState()
    val cachedCount: Int? = if (useMediator && cachedCountFlow != null) {
        cachedCountFlow.collectAsState(initial = 0).value
    } else {
        null
    }
    val lastMinSavedKey: Int? = if (useMediator && lastMinSavedKeyFlow != null) {
        lastMinSavedKeyFlow.collectAsState(initial = null).value
    } else {
        null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            UserListTopBar(
                totalUsers = pagingData?.data?.size ?: 0,
                loadState = pagingData?.loadState ?: LoadState.Loading,
                useMediator = useMediator,
                onUseMediatorChange = onToggleUseMediator,
                cachedCount = cachedCount,
                lastMinSavedKey = lastMinSavedKey,
            )
        },
        floatingActionButton = {
            if (useMediator) {
                ExtendedFloatingActionButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            pagingData?.let { data ->
                if (data.data.isEmpty()) {
                    // Empty state
                    EmptyState(modifier = Modifier.align(Alignment.Center))
                } else {
                    // User list with pagination
                    UserList(pagingData = data, listState = listState)
                }

                // Error handling overlay
                val loadState = data.loadState
                if (loadState is LoadState.Error) {
                    ErrorOverlay(
                        error = loadState.throwable,
                        onRetry = { data.retry(loadState.key) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/**
 * The list itself, taking the snapshot as a parameter.
 *
 * `PagingData` is only a skippable parameter because the repository-root
 * `compose_compiler_config.conf` declares it stable - `paging-core` is built without the Compose
 * compiler plugin, so nothing else would tell the compiler that a snapshot never changes under it.
 * Without that, this composable would re-run on every emission of the pager.
 */
@Composable
private fun UserList(pagingData: PagingData<User>, listState: LazyListState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 32.dp, // Extra space for scrollbar
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                count = pagingData.data.size,
                key = { it },
            ) { index ->
                when (val userEntry = pagingData.data[index]) {
                    EntryState.Loading -> {
                        LoadingItem(index)
                    }

                    is EntryState.Success -> {
                        UserCard(
                            user = userEntry.value,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            // Global loading indicator
            if (pagingData.loadState == LoadState.Loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }

        // Vertical Scrollbar
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(12.dp)
                .padding(end = 4.dp),
        )
    }
}
