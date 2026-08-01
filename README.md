# Paging KMP — Kotlin Multiplatform Paging Library

[![CI](https://github.com/White-Wind-LLC/paging-kmp/actions/workflows/ci.yml/badge.svg)](https://github.com/White-Wind-LLC/paging-kmp/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ua.wwind.paging/paging-core?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/ua.wwind.paging/paging-core)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)

**A lightweight, position-based paging library for Kotlin Multiplatform** — with intelligent preloading,
memory-bounded caching, offline-first sync, and **real-time pagination over Server-Sent Events (SSE) / WebSockets**.
One paging engine for **Android, iOS, JVM, macOS, Linux, Windows, JS, and WebAssembly**.

Build infinite-scroll lists, virtualized tables, and live-updating feeds with **Jetpack Compose / Compose Multiplatform**
— or any Flow-based UI — from a single shared codebase.

> Looking for a **multiplatform alternative to Jetpack Paging 3** that also runs on iOS and the web, supports
> jump-to-position random access, and can stream live updates? That's what this library is for.
> See [Comparison with Jetpack Paging 3](#comparison-with-jetpack-paging-3).

---

## Why Paging KMP?

- 🌍 **Truly multiplatform** — the same paging logic runs on every Kotlin target. No Android dependencies in the core.
- 📍 **Position-based (indexed) paging** — items are addressed by absolute integer positions, enabling **random access,
  jump-to-index, sparse loading, and correctly sized scrollbars** out of the box.
- ⚡ **Intelligent preloading & bounded cache** — data is fetched in chunks around the user's position; items far from the
  viewport are evicted automatically to keep memory flat on long lists.
- 🔌 **Offline-first** — coordinate a local cache with a remote API via `PagingMediator` (a multiplatform take on
  `RemoteMediator`): serve cache first, fetch missing ranges, reconcile totals.
- 📡 **Real-time streaming pagination** — `StreamingPager` keeps paginated lists **live** by streaming the total count and
  individual page windows over **SSE or WebSockets**. ([Jump to the example ↓](#-real-time-pagination-over-sse-streamingpager))
- 🧩 **Compose-ready** — immutable `PersistentMap`-backed snapshots and a `Flow<PagingData<T>>` API that drops straight
  into `LazyColumn` / `LazyList`.
- 🧵 **Coroutines-native & lifecycle-aware** — non-blocking, `Mutex`-guarded, debounced loading; all internal jobs are
  bound to the collection lifecycle of the returned `Flow` and cancel automatically.

## Pagers at a glance

| Pager | Use it when | Source |
|-------|-------------|--------|
| **`Pager`** | You load pages on demand from a single source (REST, DB, file). | `(pos, size) -> Flow<DataPortion<T>>` |
| **`PagingMediator`** | You want **offline-first**: read from a local cache, fall back to the network for misses. | `LocalDataSource` + `RemoteDataSource` |
| **`StreamingPager`** *(experimental)* | Your data is **live** and pushed from the server (SSE / WebSocket). | `readTotal(): Flow<Int>` + `readPortion(start, size): Flow<Map<Int, T>>` |

---

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
- [Compose Integration](#compose-integration)
- [📡 Real-time Pagination over SSE (StreamingPager)](#-real-time-pagination-over-sse-streamingpager)
- [Offline-first with PagingMediator](#offline-first-with-pagingmediator)
- [Data Mapping](#data-mapping)
- [Supported Platforms](#supported-platforms)
- [Comparison with Jetpack Paging 3](#comparison-with-jetpack-paging-3)
- [Samples](#samples)
- [License](#license)
- [Contributing](#contributing)

---

## Installation

Available on **Maven Central**.

```kotlin
// build.gradle.kts
dependencies {
    implementation("ua.wwind.paging:paging-core:2.2.7")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
}
```

Prerequisites: Kotlin `2.3.10+`, `kotlinx-collections-immutable` `0.4.0+`, and the `mavenCentral()` repository.

## Quick Start

```kotlin
data class User(val id: Int, val name: String, val email: String)

val pager = Pager<User>(
    loadSize = 20,      // items fetched per request
    preloadSize = 60,   // items kept warm around the current position
    cacheSize = 100,    // max items retained in memory
    readData = { position, loadSize ->
        flow {
            val users = repository.getUsers(position, loadSize)
            emit(
                DataPortion(
                    totalSize = repository.getTotalCount(),
                    values = users
                        .mapIndexed { index, user -> (position + index) to user }
                        .toMap()
                        .toPersistentMap()
                )
            )
            // You may emit additional portions progressively if your source streams partial results.
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

    // Access items by absolute position; accessing an item triggers loading if needed
    when (val firstUser = pagingData.data[0]) {
        EntryState.Loading -> showItemLoader()
        is EntryState.Success -> displayUser(firstUser.value)
    }
}
```

## Core Concepts

### Positional keys (Int)

Every item is addressed by an **absolute, zero-based integer position** in the underlying dataset (for a given
query/filter). This is what enables random access, jump-to-index, and accurate scrollbars.

- Use absolute positions as map keys in `DataPortion.values`.
- If your backend returns only offset + a list, generate keys client-side as `startPosition + indexInPortion`.
- Persist items in your local cache under the same absolute positions so `Pager`, `PagingMediator`, and `StreamingPager`
  can merge and window data reliably.

### State model

| Type | Purpose |
|------|---------|
| `PagingData<T>` | Immutable snapshot: the data window, the global `loadState`, and a `retry(key)` callback. |
| `PagingMap<T>` | Sparse `position -> item` map. Reading a position notifies the pager (`onGet`) and may trigger a load. Knows the total `size`. |
| `LoadState` | Global state: `Loading` · `Success` · `Error(throwable, key)`. |
| `EntryState<T>` | Per-item state: `Loading` or `Success(value)`. Use `getOrNull()` for a quick value-or-null read. |
| `DataPortion<T>` | The contract returned by your data source: `totalSize` + a `PersistentMap<Int, T>` of loaded values. |

## Compose Integration

```kotlin
@Composable
fun UserList() {
    val pagingData by pager.flow.collectAsState(initial = PagingData.empty())

    LazyColumn {
        items(count = pagingData.data.size) { index ->
            when (val entry = pagingData.data[index]) {   // accessing an item drives preloading
                EntryState.Loading -> LoadingItem()
                is EntryState.Success -> UserItem(entry.value)
            }
        }
    }

    when (pagingData.loadState) {
        LoadState.Loading -> CircularProgressIndicator()
        is LoadState.Error -> ErrorMessage(pagingData.loadState.throwable) {
            pagingData.retry(pagingData.loadState.key)
        }
        LoadState.Success -> Unit
    }
}
```

---

## 📡 Real-time Pagination over SSE (StreamingPager)

> **The standout feature.** Most paging libraries assume a request/response data source where each page is fetched once.
> `StreamingPager` is built for **live data**: it keeps a long-lived stream open for every on-screen page (a *portion*).
> When anything inside a page changes on the server, the server re-emits that **whole portion** over SSE (or WebSocket),
> and the pager swaps that page into the list in place — other pages stay untouched, and you never poll or refresh
> manually. The unit of a live update is a **portion (page of `loadSize` items)**, not an individual row.

`StreamingPager` splits a live source into two independent streams:

- **`readTotal(): Flow<Int>`** — a continuous stream of the global item count. Drives list size and pruning, and lets the
  list grow/shrink in real time.
- **`readPortion(start, size): Flow<Map<Int, T>>`** — a long-lived stream for a single page-sized window (no totals).
  **Each emission carries the current contents of that whole portion** (keyed by absolute position); the pager merges it
  into the cache, so a change anywhere in the range re-pushes and re-renders that entire page.

It then:

- Opens chunk-aligned portion streams inside an active window around the last accessed position, preloading
  `preloadSize` in both directions.
- Closes streams gracefully once the viewport moves farther than `closeThreshold` away — so you only hold open
  subscriptions for what's on screen.
- Keeps a bounded cache window (`cacheSize`) and prunes out-of-bounds items.
- Aggregates per-window `LoadState` (priority: `Loading > Error > Success`) and **survives `readTotal` failures**,
  recovering on `retry`.

Perfect for **live dashboards, trading/price tables, chat & activity feeds, collaborative lists, and order/inventory
boards** — anywhere the data changes while the user is looking at it.

### Example: Ktor SSE client

```kotlin
@Serializable
data class User(val id: Int, val name: String, val email: String)

// Ktor HttpClient with SSE support
val client = HttpClient(CIO) {
    install(SSE)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

// Stream 1: live total count (server emits an integer in each event's `data:`)
fun totalCountFlow(): Flow<Int> = flow {
    client.sse(method = HttpMethod.Get, urlString = "https://api.example.com/users/total/stream") {
        incoming.collect { event ->
            val value = event.data?.trim()?.toIntOrNull() ?: return@collect
            emit(value)
        }
    }
}

// Stream 2: one live page. The server (re)emits the WHOLE portion for this range
// as a JSON array whenever any item inside it changes.
fun userPortionFlow(position: Int, size: Int): Flow<Map<Int, User>> = flow {
    val url = "https://api.example.com/users/portion?start=$position&size=$size"
    client.sse(method = HttpMethod.Get, urlString = url) {
        incoming.collect { event ->
            val users: List<User> = Json.decodeFromString(event.data ?: return@collect)
            // Map to absolute positions: position..position + size - 1
            emit(users.mapIndexed { idx, user -> (position + idx) to user }.toMap())
        }
    }
}

@OptIn(ExperimentalStreamingPagerApi::class)
val pager = StreamingPager<User>(
    config = StreamingPagerConfig(
        loadSize = 20,
        preloadSize = 60,
        cacheSize = 100,
        closeThreshold = 20,
        keyDebounceMs = 300,
    ),
    readTotal = { totalCountFlow() },
    readPortion = { position, size -> userPortionFlow(position, size) },
)

// Consume exactly like Pager — the list now updates itself in real time
pager.flow.collect { pagingData ->
    when (pagingData.loadState) {
        LoadState.Loading -> showLoader()
        LoadState.Success -> hideLoader()
        is LoadState.Error -> pagingData.retry(pagingData.loadState.key)
    }
    when (val firstUser = pagingData.data[0]) {
        EntryState.Loading -> showItemLoader()
        is EntryState.Success -> displayUser(firstUser.value)
    }
}
```

> The same model works with **WebSockets** or any other push transport — just back `readTotal` / `readPortion` with the
> flow of your choice. Positions must be absolute across the dataset; when the total shrinks, out-of-bounds streams are
> cancelled and cached values pruned automatically.

A complete, editable live-list demo ships in the `paging-samples` module (`StreamingUserListScreen`).

---

## Offline-first with PagingMediator

`PagingMediator<T, Q>` coordinates a **local cache** with a **remote source** while preserving positional paging. It
serves local data first, then fetches the missing ranges — a multiplatform analogue to Paging 3's `RemoteMediator`, with
per-query flows.

```kotlin
class UserLocalDataSource(private val dao: UserDao) : LocalDataSource<User, Unit> {
    override suspend fun read(startPosition: Int, size: Int, query: Unit): DataPortion<User> =
        dao.readPortion(startPosition, size)

    override suspend fun save(portion: DataPortion<User>) = dao.upsertPortion(portion)
    override suspend fun clear() = dao.clearAll()
}

class UserRemoteDataSource(private val api: UserApi) : RemoteDataSource<User, Unit> {
    override suspend fun fetch(startPosition: Int, size: Int, query: Unit): DataPortion<User> =
        api.fetchUsers(startPosition, size)
}

val mediator = PagingMediator(
    local = UserLocalDataSource(dao),
    remote = UserRemoteDataSource(api),
    config = PagingMediatorConfig(
        loadSize = 20,                  // items per page
        prefetchSize = 60,              // items preloaded around the current position
        cacheSize = 100,                // max items kept in memory
        concurrency = 2,                // concurrent remote fetches
        isRecordStale = { false },      // decide if a cached record must be refreshed
        fetchFullRangeOnMiss = false,   // refetch the full window on miss/inconsistency
        emitOutdatedRecords = false,    // emit stale records while refreshing
        emitIntermediateResults = true, // emit partial results as ranges arrive
    ),
)

// Each query owns its own paging flow; pass Unit if you don't filter
mediator.flow(Unit).collect { pagingData ->
    // Same UI handling as Pager / StreamingPager
}
```

Key behaviors: emits cached records first (optionally including stale ones), then remote updates; detects inconsistent
total sizes and refetches/clears the window when needed; supports configurable parallel fetches and intermediate
emissions.

## Data Mapping

Transform items while preserving loading state and retry logic:

```kotlin
val mapped: PagingData<String> = pagingData.map { user -> "${user.id}: ${user.name}" }
// Only currently-loaded items are transformed; loadState and retry are preserved.
```

## Supported Platforms

| Platform | Targets |
|----------|---------|
| **Android** | API 21+ |
| **JVM** | Java 17+ |
| **iOS** | iosX64, iosArm64, iosSimulatorArm64 |
| **macOS** | macosX64, macosArm64 |
| **Linux** | linuxX64, linuxArm64 |
| **Windows** | mingwX64 |
| **Web** | JavaScript (Node), WebAssembly (WasmJs) |

## Comparison with Jetpack Paging 3

| | **Paging KMP** | **Jetpack Paging 3** |
|---|---|---|
| Platforms | Android, iOS, JVM, macOS, Linux, Windows, JS, Wasm | Primarily Android/JVM (multiplatform support is limited) |
| Paging model | **Position/index-based** (random access, jump-to-index) | Cursor/key-based (sequential) |
| Live/streaming updates | ✅ Built-in via `StreamingPager` (SSE / WebSocket) | ❌ Not designed for it |
| Offline-first | ✅ `PagingMediator` | ✅ `RemoteMediator` |
| UI | Compose Multiplatform & any Flow consumer | Compose / RecyclerView (Android) |
| Dependencies | Coroutines + immutable collections (no Android in core) | AndroidX |

Choose Paging KMP when you target more than Android, need positional/random access, or want **real-time** paginated
lists. Choose Paging 3 if you're Android-only and happy with cursor-based paging.

## Samples

The `paging-samples` module contains complete, runnable Compose Multiplatform examples:

- Basic on-demand paging (`Pager`)
- Offline-first local + remote sync (`PagingMediator`)
- **Live streaming list with editing (`StreamingPager`)**
- Error handling, retry, and scrollbar integration

## License

Licensed under the **Apache License 2.0**. See [`LICENSE`](LICENSE) for details.

## Contributing

PRs and discussions are welcome! Please add an example to `paging-samples` for new features.

Code style is enforced by the build, not by review:

```bash
./gradlew spotlessApply          # format all Kotlin sources — run before committing
./gradlew spotlessCheck detekt   # verify formatting and run static analysis
```

Spotless (ktlint) owns formatting, detekt owns code smells; the two rule sets do not overlap. ktlint settings live in
the root `build.gradle.kts`, deliberate deviations from detekt's default rules live in
[`config/detekt/detekt.yml`](config/detekt/detekt.yml), and [`.editorconfig`](.editorconfig) holds the settings your
editor reads. Both checks are part of `./gradlew check` and run as the `Static analysis` job in CI.

The repository was reformatted in a single commit. To keep `git blame` readable, run once:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

---

<sub>Keywords: Kotlin Multiplatform paging, Compose Multiplatform pagination, KMP infinite scroll, Jetpack Paging 3
alternative, iOS Kotlin paging, SSE pagination, WebSocket pagination, real-time paginated list, offline-first paging,
position-based paging.</sub>
