# Paging Sample

Demonstration of the Kotlin Multiplatform Paging library with a scrollable user list.

## What's Implemented

**Paging Configuration:**

```kotlin
Pager<User>(
    loadSize = 20,      // Items per request
    preloadSize = 60,   // Items preloaded around position  
    cacheSize = 100,    // Items kept in memory
    scope = scope,
    readData = ::loadUsers
)
```

**Data Source:**

- 2,500 mock users with simulated network delays
- 5% random error rate for testing retry functionality

**Paging Integration:**

```kotlin
// Repository to Pager data conversion
private suspend fun loadUsers(position: Int, loadSize: Int): DataPortion<User> {
    val offset = position - 1 // Convert from 1-based to 0-based
    val userPage = userRepository.getUsers(offset, loadSize)
    
    val userMap = userPage.users.mapIndexed { index, user ->
        (position + index) to user
    }.toMap()
    
    return DataPortion(
        totalSize = userPage.totalCount,
        values = userMap
    )
}
```

**UI Integration:**

```kotlin
val pagingData by viewModel.pagingFlow.collectAsState()

LazyColumn {
    items(count = data.data.size) { index ->
        val position = index + 1
        when (val entry = data.data[position]) {
            EntryState.Loading -> LoadingItem()
            is EntryState.Success -> UserCard(entry.value)
        }
    }
}
```

## Running

```bash
# Desktop
./gradlew :paging-samples:run

# JS
./gradlew :paging-samples:jsBrowserDevelopmentRun --continue

#Wasm
./gradlew :paging-samples:wasmJsBrowserDevelopmentRun --continue
```

## Key Points

1. **1-based indexing**: Pager uses 1-based positions internally
2. **Automatic loading**: Items load as you scroll, with intelligent preloading
3. **Memory management**: Cache automatically removes distant items
4. **Error handling**: Network errors show retry overlay
5. **Loading states**: Individual items and global loading indicators
