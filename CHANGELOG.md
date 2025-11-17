# Changelog

All notable changes to this project will be documented in this file.

## [2.2.3] - 2025-11-18

### Changed

- Migrate to immutable collections: `PagingMap`, `DataPortion`, and all paging data structures now use
  `kotlinx.collections.immutable.PersistentMap` instead of standard `Map` for better efficiency with Jetpack Compose
  and reactive UI frameworks.
- Added `kotlinx-collections-immutable` dependency to `paging-core` and `paging-samples`.

### Migration Guide

- The public API surface remains compatible; `PersistentMap` implements `Map` interface, so existing read operations
  continue to work.

## [2.2.2] - 2025-11-05

### Breaking Changes

- Pager/PagingMediator lifecycle: both are now lifecycle-aware using `channelFlow`; the explicit `CoroutineScope`
  constructor parameter was removed:
  - `Pager<T>(loadSize, preloadSize, cacheSize, readData)` (removed `scope`)
  - `PagingMediator<T, Q>(local, remote, config)` (removed `scope`)
    All internal jobs are bound to the collection lifecycle of the returned `Flow`.
- StreamingPager package and constructor:
  - Moved to `ua.wwind.paging.core.stream.StreamingPager` (was `ua.wwind.paging.core.StreamingPager`).
  - Removed `CoroutineScope` constructor parameter; the pager is now lifecycle-aware via `channelFlow`.
    Update imports and remove `scope = ...` when instantiating.

### Changed

- StreamingPager internals extracted into `StreamingPagerState` and `WindowHelpers` for clarity and testability.
- Jobs across `Pager`, `PagingMediator`, and `StreamingPager` are bound to the active flow collection and are
  cancelled automatically on collector cancellation.
- Bump Kotlin to 2.2.21.

### Migration Guide

- Update imports: `StreamingPager` is now at `ua.wwind.paging.core.stream.StreamingPager`.
- Remove `scope` argument from `Pager`, `PagingMediator`, and `StreamingPager` constructors.
- No API changes to data models (`PagingData`, `PagingMap`, `LoadState`) or mediator/local/remote interfaces.

## [2.2.1] - 2025-09-13

### Breaking Changes

- Positions are now treated consistently as zero-based across the library. This affects `Pager`, `PagingMediator`,
  `StreamingPager`, and all sample code. If you previously used one-based positions, remove any `+1`/`-1` conversions at
  call sites and in your data mappers.
- `PagingMap.firstKey()`/`lastKey()` now return `-1` when no data is loaded (was `0`). Use `>= 0` to check for presence.

### Changed

- Internal logic, tests, and samples updated to use zero-based positions; docs and KDoc wording made neutral (no
  explicit mention of indexing base) to match common developer expectations.
- `StreamingPager` startup and window alignment now operate from position `0` for empty-state initialization.
- README examples updated for the new positional semantics and simplified wording.

### Fixed

- Stabilized `StreamingPagerTest` expectations around range alignment and emissions across platforms.

### Migration Guide

- Remove any `position - 1` or `index + 1` conversions in your UI and data adapters. Keys passed to and emitted by the
  library should be absolute zero-based positions.
- When checking `firstKey()`/`lastKey()`, treat `-1` as “no data yet”.

## [2.1.0] - 2025-09-12

### Added

- StreamingPager (experimental): streaming-first pager that:
    - Uses a dedicated total-size flow `readTotal(): Flow<Int>` and per-range flows
      `readPortion(start, size): Flow<Map<Int,T>>`.
    - Opens chunk-aligned flows around the last accessed key and merges values into a bounded cache window.
    - Closes flows gracefully when the active window moves farther than a configurable threshold.
    - Aggregates per-range load states into a global `LoadState` (Loading > Error > Success).
    - Configurable via `StreamingPagerConfig` (loadSize, preloadSize, cacheSize, closeThreshold, keyDebounceMs).
- Kermit logging initialization via BuildKonfig property `LOG_LEVEL` (Debug by default; set `-PLOG_LEVEL=Info` for
  publish).
- Samples: added tabbed demo, including StreamingPager example with live list editing.
- Update versions: kotlin to 2.2.10

### Changed

- README: added StreamingPager section with Ktor SSE usage example; installation snippet now references 2.1.0.

## [2.0.1] - 2025-09-05

### Breaking Changes

- `LocalDataSource` is now generic on query type and requires a query when reading:
    - `LocalDataSource<T>` → `LocalDataSource<T, Q>`
    - `read(startPosition: Int, size: Int)` → `read(startPosition: Int, size: Int, query: Q)`
    - `PagingMediator<T, Q>` constructor and internals updated accordingly to pass the query to local reads.
    - Action required: update your `LocalDataSource` implementations and call sites to include the `query` parameter.

### Changed

- `PagingMediator` now forwards the active query to the local data source for consistent per-query reads.

### Documentation

- README: clarified version catalog usage and removed redundant example to avoid duplication.

### Chore

- Updated `.gitignore` and removed stray `local.properties` from version control.

## [2.0.0] - 2025-09-04

### Breaking Changes

- `Pager` API change: `readData` now returns a `Flow<DataPortion<T>>` and is no longer a `suspend` function. Signature
  changed from
  `suspend (pos: Int, loadSize: Int) -> DataPortion<T>` to `(pos: Int, loadSize: Int) -> Flow<DataPortion<T>>`.
  This enables streaming of partial results and incremental UI updates while a range is loading.
- Loading behavior updated: cache window enforcement now happens continuously while streaming; clients relying on
  the previous single-shot update per range may need to adapt to incremental emissions.

### Added

- `PagingMediator<T, Q>`: a high-level coordinator between a local cache and a remote source that exposes
  `Flow<PagingData<T>>` per query. Key features:
    - Emits local data first (optionally including stale records), then missing ranges fetched from the remote.
    - Detects total-size inconsistencies; clears local cache and refetches the requested window when needed.
    - Supports parallel remote fetches with configurable concurrency and optional intermediate emissions.
    - Configurable via `PagingMediatorConfig` (load size, prefetch size, cache size, concurrency, and more).
    - Pluggable `LocalDataSource` and `RemoteDataSource` abstractions.

### Changed

- `Pager` now updates internal state incrementally while collecting `readData` emissions.
- Minor KDoc updates and improved test coverage for cache window vs. preload range behavior.

## [1.0.1] - 2025-09-02

### Added

- Data mapping capability in `PagingData` via `PagingData.map { }` (transforms currently loaded items while preserving
  `loadState` and `retry`).
- Tests: `PagerTest`, `PagingDataTest`.
- License and notice files: `LICENSE`, `NOTICE`.

### Changed

- Maven Central publishing configuration refinements; publish/build only `:paging-core` module.
- CI: updated GitHub Actions workflow (conditional samples exclusion, Gradle wrapper executable, Yarn lock handling for
  Kotlin/WASM).
- README improvements and documentation updates.

### Fixed

- Maven publishing setup reliability.

## [1.0.0] - 2025-08-24

- Initial release.
