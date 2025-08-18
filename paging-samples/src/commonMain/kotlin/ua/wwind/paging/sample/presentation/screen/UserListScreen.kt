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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.wwind.paging.core.EntryState
import ua.wwind.paging.core.LoadState
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
    modifier: Modifier = Modifier,
) {
    val pagingData by viewModel.pagingFlow.collectAsState(initial = null)
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            UserListTopBar(
                totalUsers = pagingData?.data?.size ?: 0,
                loadState = pagingData?.loadState ?: LoadState.Loading
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            pagingData?.let { data ->
                if (data.data.isEmpty()) {
                    // Empty state
                    EmptyState(modifier = Modifier.align(Alignment.Center))
                } else {
                    // User list with pagination
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 16.dp,
                                end = 32.dp, // Extra space for scrollbar
                                bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                count = data.data.size,
                                key = { index -> index + 1 } // 1-based indexing
                            ) { index ->
                                val position = index + 1
                                when (val userEntry = data.data[position]) {
                                    EntryState.Loading -> {
                                        LoadingItem()
                                    }

                                    is EntryState.Success -> {
                                        UserCard(
                                            user = userEntry.value,
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                            }

                            // Global loading indicator
                            if (data.loadState == LoadState.Loading) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.padding(16.dp)
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
                                .padding(end = 4.dp)
                        )
                    }
                }

                // Error handling overlay
                val loadState = data.loadState
                if (loadState is LoadState.Error) {
                    ErrorOverlay(
                        error = loadState.throwable,
                        onRetry = { data.retry(loadState.key) },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}