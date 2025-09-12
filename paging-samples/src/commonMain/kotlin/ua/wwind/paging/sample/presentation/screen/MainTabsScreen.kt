package ua.wwind.paging.sample.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ua.wwind.paging.sample.data.local.InMemoryUserLocalDataSource
import ua.wwind.paging.sample.data.local.SharedEditableUsersStore
import ua.wwind.paging.sample.data.repository.FakeUserRemoteDataSource
import ua.wwind.paging.sample.presentation.viewmodel.StreamingUserListViewModel
import ua.wwind.paging.sample.presentation.viewmodel.UserListViewModel

@Composable
fun MainTabsScreen(
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val selectedTab = remember { mutableIntStateOf(0) }

    Column {
        TabRow(selectedTabIndex = selectedTab.value) {
            Tab(selected = selectedTab.value == 0, onClick = { selectedTab.value = 0 }, text = { Text("Pager") })
            Tab(
                selected = selectedTab.value == 1,
                onClick = { selectedTab.value = 1 },
                text = { Text("StreamingPager") })
        }

        when (selectedTab.value) {
            0 -> {
                val remote = FakeUserRemoteDataSource()
                val local = InMemoryUserLocalDataSource()
                val vm = UserListViewModel(
                    remote = remote,
                    local = local,
                    useMediator = false,
                    scope = scope
                )
                val useMediator = remember { mutableStateOf(false) }
                UserListScreen(
                    viewModel = vm,
                    useMediator = useMediator.value,
                    onToggleUseMediator = { useMediator.value = it },
                    onRefresh = {
                        scope.launch {
                            vm.clearCache()
                        }
                    },
                    cachedCountFlow = local.cachedCount,
                    lastMinSavedKeyFlow = local.lastSavedMinKey,
                    modifier = modifier
                )
            }

            else -> {
                val store = SharedEditableUsersStore(initialSize = 500)
                val vm = StreamingUserListViewModel(store = store, scope = scope)
                StreamingUserListScreen(viewModel = vm, modifier = modifier)
            }
        }
    }
}
