# Kotlin Multiplatform Paging Library

[![Maven Central](https://img.shields.io/maven-central/v/ua.wwind.paging/paging-core)](https://central.sonatype.com/artifact/ua.wwind.paging/paging-core)

Lightweight and efficient paging library for Kotlin Multiplatform with intelligent preloading, caching, and coroutines
support.

## Features

- **KMP-first**: Works across JVM/Android/JS/Native platforms
- **Intelligent preloading**: Automatically loads data around current position
- **Memory efficient**: Configurable cache size with automatic cleanup
- **Coroutines-based**: Non-blocking data loading with proper cancellation support
- **Debounced loading**: Prevents excessive requests during fast scrolling (300ms debounce)
- **Error handling**: Built-in error states with retry functionality
- **Thread-safe**: Uses Mutex for safe concurrent access
- **Reactive**: Flow-based API for real-time updates

## Installation

Prerequisites: Kotlin `2.2.0`, repository `mavenCentral()`.

```kotlin
// build.gradle.kts
dependencies {
    implementation("ua.wwind.paging:paging-core:2.0.1")
}
```

## Quick Start

```kotlin
data class User(val id: Int, val name: String, val email: String)

val pager = Pager<User>(
    loadSize = 20,
    preloadSize = 60,
    cacheSize = 100,
    scope = coroutineScope,
    readData = { position, loadSize ->
        kotlinx.coroutines.flow.flow {
            val users = repository.getUsers(position - 1, loadSize) // Convert to 0-based
            emit(
                DataPortion(
                    totalSize = repository.getTotalCount(),
                    values = users.mapIndexed { index, user ->
                        (position + index) to user
                    }.toMap()
                )
            )
            // Optionally emit more portions progressively if your source supports it
        }
    }
)

// Observe paging data
pager.flow.collect { pagingData ->
    when (pagingData.loadState) {
        LoadState.Loading -> showLoader()
        LoadState.Success -> hideLoader()
        is LoadState.Error -> pagingData.retry(pagingData.loadState.key)
    }
    
    // Access items by position (1-based indexing)
    when (val firstUser = pagingData.data[1]) {
        EntryState.Loading -> showItemLoader()
        is EntryState.Success -> displayUser(firstUser.value)
    }
}
```

## Positional Keys (Int)

For caching and paging to work correctly, items must be addressable by an integer positional key (Int):

- Use absolute 1-based positions as map keys. These keys represent the item order in your external data source for a
  given query/filter.
- If your backend does not provide positions, you can generate them client-side as `startPosition + indexInPortion` when
  building `DataPortion.values`.
- Persist items in your local cache using these absolute positions as keys so `Pager` and `PagingMediator` can merge and
  window data reliably.

## UI Integration (Compose)

```kotlin
@Composable
fun UserList() {
    val pagingData by pager.flow.collectAsState()

    LazyColumn {
        items(count = pagingData.data.size) { index ->
            val position = index + 1
            when (val entry = pagingData.data[position]) {
                EntryState.Loading -> LoadingItem()
                is EntryState.Success -> UserItem(entry.value)
            }
        }
    }

    // Handle loading state
    when (pagingData.loadState) {
        LoadState.Loading -> CircularProgressIndicator()
        is LoadState.Error -> ErrorMessage(pagingData.loadState.throwable) {
            pagingData.retry(pagingData.loadState.key)
        }
        LoadState.Success -> Unit
    }
}
```

## Supported Platforms

Android (API 21+) • JVM (Java 17+) • iOS • macOS • Linux • Windows • JavaScript • WebAssembly

## Examples

The `paging-samples` module contains complete working examples:

- Basic paging implementation
- Database integration
- Network API integration
- Error handling and retry logic
- UI integration patterns

## Data Mapping
You can transform items of `PagingData` while preserving loading state and retry logic.
```kotlin
// Given: PagingData<User>
val mapped: PagingData<String> = pagingData.map { user -> "${user.id}: ${user.name}" }

// Notes:
// - Only currently loaded items are transformed
// - loadState and retry remain unchanged
```

## PagingMediator

Coordinate a local cache with a remote source while preserving positional paging. `PagingMediator<T, Q>` emits
`Flow<PagingData<T>>` per query, serving local data first and fetching missing ranges from the network.

Key capabilities:

- Emits local records first (optionally including stale ones), then remote updates
- Detects inconsistent total sizes and refetches the requested window, clearing cache when needed
- Optional intermediate emissions and configurable parallel fetches

Define data sources:

```kotlin
class UserLocalDataSource(
    private val dao: UserDao
) : LocalDataSource<User> {
    override suspend fun read(startPosition: Int, size: Int, query: Unit): DataPortion<User> =
        dao.readPortion(startPosition, size)

    override suspend fun save(portion: DataPortion<User>) {
        dao.upsertPortion(portion)
    }

    override suspend fun clear() {
        dao.clearAll()
    }
}

class UserRemoteDataSource(
    private val api: UserApi
) : RemoteDataSource<User, Unit> {
    override suspend fun fetch(startPosition: Int, size: Int, query: Unit): DataPortion<User> =
        api.fetchUsers(startPosition, size)
}
```

Create mediator and collect:

```kotlin
val mediator = PagingMediator(
    scope = coroutineScope,
    local = UserLocalDataSource(dao),
    remote = UserRemoteDataSource(api),
    config = PagingMediatorConfig(
        loadSize = 20,                  // Number of items loaded per page
        prefetchSize = 60,              // Number of items to preload around current position
        cacheSize = 100,                // Max number of items to keep in memory cache
        concurrency = 2,                // Number of concurrent fetches allowed
        isRecordStale = { false },      // Function to check if a record is outdated
        fetchFullRangeOnMiss = false,   // Whether to refetch full range if data is missing or inconsistent
        emitOutdatedRecords = false,    // Emit outdated records while fetching new ones
        emitIntermediateResults = true, // Emit partial/intermediate load results during fetch
    )
)

// Each query owns its own paging flow; use Unit if no filtering is needed
mediator.flow(Unit).collect { pagingData ->
    // Same UI handling as with Pager
}
```

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for details.

## Contributing

PRs and discussions are welcome. Please maintain code style and add examples to `paging-samples` for new features.