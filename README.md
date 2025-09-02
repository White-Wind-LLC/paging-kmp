# Kotlin Multiplatform Paging Library

+

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
    implementation("ua.wwind.paging:paging-core:1.0.1")
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
        val users = repository.getUsers(position - 1, loadSize) // Convert to 0-based
        DataPortion(
            totalSize = repository.getTotalCount(),
            values = users.mapIndexed { index, user ->
                (position + index) to user
            }.toMap()
        )
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

## License

This project is licensed under the Apache License 2.0. See `LICENSE` for details.

## Contributing

PRs and discussions are welcome. Please maintain code style and add examples to `paging-samples` for new features.