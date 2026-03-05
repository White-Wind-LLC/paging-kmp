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

Prerequisites: Kotlin `2.3.0`, `org.jetbrains.kotlinx:kotlinx-collections-immutable` `0.4.0` or higher, repository
`mavenCentral()`.

```kotlin
// build.gradle.kts
dependencies {
    implementation("ua.wwind.paging:paging-core:2.2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
}
```

## Quick Start

```kotlin
data class User(val id: Int, val name: String, val email: String)

val pager = Pager<User>(
    loadSize = 20,
    preloadSize = 60,
    cacheSize = 100,
    readData = { position, loadSize ->
        flow {
            val users = repository.getUsers(position, loadSize)
            emit(
                DataPortion(
                    totalSize = repository.getTotalCount(),
                    values = users.mapIndexed { index, user ->
                        (position + index) to user
                    }.toMap()
                        .toPersistentMap()
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

    // Access items by position
    when (val firstUser = pagingData.data[0]) {
        EntryState.Loading -> showItemLoader()
        is EntryState.Success -> displayUser(firstUser.value)
    }
}
```

## Positional Keys (Int)

For caching and paging to work correctly, items must be addressable by an integer positional key (Int):

- Use absolute positions as map keys. These keys represent the item order in your external data source for a
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
            when (val entry = pagingData.data[index]) {
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

## StreamingPager

If your data source is streaming and you can emit updates for the total item count and for individual portions
independently, use `StreamingPager`. It opens/closes portion flows dynamically around the last accessed position, while
a dedicated total-size flow keeps the item count in sync.

- Separate streams:
    - `readTotal(): Flow<Int>` emits global total count updates.
    - `readPortion(start, size): Flow<Map<Int, T>>` emits only data maps for the requested range (no total).
- Window-based, chunk-aligned opening with `config.loadSize` and preloading via `config.preloadSize`.
- Graceful closing when the window moves farther than `config.closeThreshold` from a range.
- Bounded cache window via `config.cacheSize`.
- Aggregated `LoadState` across opened ranges (Loading > Error > Success).

```kotlin
@Serializable
data class User(val id: Int, val name: String, val email: String)

// Ktor HttpClient with SSE
val client = HttpClient(CIO) {
    install(SSE)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

// SSE stream with total size updates (server emits integer in `data:` of each event)
fun totalCountFlow(): Flow<Int> = flow {
    client.sse(method = HttpMethod.Get, urlString = "https://api.example.com/users/total/stream") {
        incoming.collect { event ->
            val value = event.data.trim().toIntOrNull() ?: return@collect
            emit(value)
        }
    }
}

// SSE stream with portion updates; server emits JSON array of users for requested range
fun userPortionFlow(position: Int, size: Int): Flow<Map<Int, User>> = flow {
    val url = "https://api.example.com/users/portion?start=$position&size=$size"
    client.sse(method = HttpMethod.Get, urlString = url) {
        incoming.collect { event ->
            val users: List<User> = Json.decodeFromString(event.data)
            // Map to absolute positions: position..position+size-1
            val values: Map<Int, User> = users.mapIndexed { idx, user -> (position + idx) to user }.toMap()
            emit(values)
        }
    }
}

// Create StreamingPager
@OptIn(ExperimentalStreamingPagerApi::class)
val pager = StreamingPager<User>(
    config = StreamingPagerConfig(
        loadSize = 20,
        preloadSize = 60,
        cacheSize = 100,
        closeThreshold = 20,
        keyDebounceMs = 300
    ),
    readTotal = { totalCountFlow() },
    readPortion = { position, size -> userPortionFlow(position, size) }
)

// Observe paging data the same way as with Pager
pager.flow.collect { pagingData ->
    when (pagingData.loadState) {
        LoadState.Loading -> showLoader()
        LoadState.Success -> hideLoader()
        is LoadState.Error -> pagingData.retry(pagingData.loadState.key)
    }

    // Access items by position
    when (val firstUser = pagingData.data[0]) {
        EntryState.Loading -> showItemLoader()
        is EntryState.Success -> displayUser(firstUser.value)
    }
}

// Notes:
// - Positions must be absolute across the dataset (same as with `Pager`).
// - When total size shrinks, the pager cancels out-of-bounds flows and prunes cached values automatically.
```

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for details.

## Contributing

PRs and discussions are welcome. Please maintain code style and add examples to `paging-samples` for new features.