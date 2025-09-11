# Changelog

All notable changes to this project will be documented in this file.

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
