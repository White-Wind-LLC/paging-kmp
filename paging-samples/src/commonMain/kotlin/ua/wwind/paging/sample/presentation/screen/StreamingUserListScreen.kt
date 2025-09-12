package ua.wwind.paging.sample.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.wwind.paging.core.EntryState
import ua.wwind.paging.core.LoadState
import ua.wwind.paging.sample.presentation.components.EmptyState
import ua.wwind.paging.sample.presentation.components.ErrorOverlay
import ua.wwind.paging.sample.presentation.components.LoadingItem
import ua.wwind.paging.sample.presentation.components.UserCard
import ua.wwind.paging.sample.presentation.viewmodel.StreamingUserListViewModel
import ua.wwind.paging.sample.scrollbar.VerticalScrollbar
import ua.wwind.paging.sample.scrollbar.rememberScrollbarAdapter

@Composable
fun StreamingUserListScreen(
    viewModel: StreamingUserListViewModel,
    modifier: Modifier = Modifier,
) {
    val pagingData by viewModel.pagingFlow.collectAsState(initial = null)

    Row(modifier = modifier.fillMaxSize()) {
        // Editor pane
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Editor", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val addPosState = remember { mutableStateOf(0) }
                Button(onClick = {
                    val pos = addPosState.value
                    viewModel.addUserAt(position = pos, user = viewModel.userAt(pos) ?: return@Button)
                }) { Text("Duplicate at pos") }
            }

            val previewCount = viewModel.totalSize()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(previewCount) { pos ->
                    val user = viewModel.userAt(pos)
                    if (user != null) {
                        Card(Modifier.fillMaxWidth().padding(2.dp)) {
                            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                var first by remember(user.id) { mutableStateOf(user.firstName) }
                                var last by remember(user.id) { mutableStateOf(user.lastName) }
                                OutlinedTextField(value = first, onValueChange = {
                                    first = it
                                    viewModel.updateUser(pos) { u -> u.copy(firstName = it) }
                                }, label = { Text("First") })
                                OutlinedTextField(value = last, onValueChange = {
                                    last = it
                                    viewModel.updateUser(pos) { u -> u.copy(lastName = it) }
                                }, label = { Text("Last") })
                                Button(onClick = { viewModel.removeUserAt(pos) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }

        // Streaming pager pane
        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
            pagingData?.let { data ->
                if (data.data.isEmpty()) {
                    EmptyState(modifier = Modifier.align(Alignment.Center))
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp, top = 16.dp, end = 32.dp, bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(count = data.data.size, key = { it }) { index ->
                                when (val entry = data.data[index]) {
                                    EntryState.Loading -> LoadingItem(index)
                                    is EntryState.Success -> UserCard(user = entry.value)
                                }
                            }
                            if (data.loadState == LoadState.Loading) {
                                item {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("Loading...")
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(12.dp)
                        )
                    }
                }
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
