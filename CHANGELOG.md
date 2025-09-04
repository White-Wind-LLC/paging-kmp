# Changelog

All notable changes to this project will be documented in this file.

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
