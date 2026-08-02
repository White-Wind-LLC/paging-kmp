# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

- Both pagers no longer oscillate between two windows when the consumer reads a span wider than the window (#45).
  Since 2.3.0 a read that falls inside the loaded window does not reach the planner (#16), so the last read that *did*
  reach it was whichever position the cache happened not to hold - and the pager prunes that cache around the very key
  it took from those reads. A consumer reading more positions than the window covers - a `LazyColumn` resolving an item
  for every key of its nearby range, not just for the rows on screen - therefore had the two ends of its read span
  evict each other in turn, and the window flipped between them for as long as the consumer kept reading: in the
  reported session 69 portion requests, 63 of them cancelled, alternating with a ~500 ms period from a stationary
  viewport. Both pagers now track every read, gated or not, and plan around where the consumer actually is; the gate
  still decides whether the planner is woken at all, so the per-row cost #16 removed stays removed. Positions past the
  window are simply left unloaded again, as they were in 2.2.7.
- `StreamingPager` acts on a `PagingData.retry` that names the position the consumer is already sitting on. Retries
  travelled through the debounced key trigger, a `StateFlow` that conflates equal values, so a retry for the current
  position emitted nothing at all and no stream was reopened; it now has its own channel, as `Pager` already did, and
  is served without waiting out the debounce.

## [2.3.0] - 2026-08-02

### Breaking Changes

- `Pager` gained `keyDebounceMs` and `concurrency` parameters between `cacheSize` and `readData`. Named arguments and
  the trailing-lambda form are unaffected; only calls that pass `readData` as the fourth *positional* argument need
  updating.
- `Pager` now fetches up to `concurrency` (default `4`) chunks in parallel, so a source that must not be hit in
  parallel has to opt out with `concurrency = 1`. `PagingMediator` is unaffected by the new default: it passes its own
  `concurrency` (default `1`) down to the pager.
- Dropped the `macosX64` (Intel macOS) target from `paging-core`; the `paging-core-macosx64` artifact is no longer
  published. Kotlin 2.4 deprecates the target and will remove it in a future release, as Apple winds down x86_64
  support. `macosArm64` (Apple Silicon) is unaffected, as are `iosX64`, `linuxX64` and `mingwX64`.

### Added

- `Pager` takes a `keyDebounceMs` (default `300`), matching `StreamingPagerConfig.keyDebounceMs`. The delay was
  hard-coded before, so a local or otherwise cheap source had no way to serve position changes sooner.
- `Pager` takes a `concurrency` (default `4`): the number of chunks one loading pass keeps in flight (#8). Chunks used
  to be fetched strictly one after another, so filling the preload window after a jump cost one round trip per chunk -
  900 ms for a ±60 item window over a 100 ms backend, of which the chunk the user is actually looking at was only the
  first 100 ms. It now costs `ceil(chunks / concurrency)` round trips (500 ms for the same jump, debounce included).

- A `compose_compiler_config.conf` at the repository root declaring every UI-facing type stable to the Compose compiler
  (#16). `paging-core` is built without the Compose compiler plugin - which is what keeps it free of a Compose runtime
  dependency - and the compiler only infers stability for classes it compiles itself, so it treated `PagingData` and
  everything around it as unstable: a composable taking one was never skippable and re-ran on every emission, however
  little had changed. Copy the file and point `composeCompiler { stabilityConfigurationFiles }` at it; the README
  documents the setup and how to verify it with a Compose compiler report.

### Changed

- Reading an already loaded position no longer notifies the pager (#16). `PagingMap.get` is what tells the pager where
  the viewport is, and a `LazyColumn` re-reads every visible row on every recomposition - each read updating a
  `MutableStateFlow` and restarting the key debounce, on the UI thread. Both pagers now track the stretch of positions
  whose access cannot change anything they would do, which is the loaded window pulled in by `loadSize` on each side,
  and reading inside it costs nothing but the lookup. Reads within a page of the window edge still move the window, and
  a `refresh()`, a failed load or a hole left by a short portion puts every read back in play.
- `Pager.flow` and `StreamingPager.flow` are now conflated (#12). `PagingData` is a complete snapshot of the list, so
  only the newest one is worth rendering, but the `channelFlow` underneath buffered 64 of them: a UI slower than the
  source - which a live SSE stream easily is - worked through a queue of states it rendered and immediately discarded.
  A collector that falls behind now receives the current state instead. Nothing is lost, since every snapshot carries
  the whole window; only intermediate `LoadState` transitions can be skipped.
- `StreamingPager` no longer re-emits its aggregated `LoadState` when it did not change (#12). The per-range map
  underneath is touched by every streamed message, while the single state derived from it moves only when a range
  actually opens or settles, so a portion arriving next to a range that was still loading used to reach the UI as two
  `PagingData` instead of one. Marking a range with the state it already holds is also skipped outright now, instead of
  rebuilding the map for it once per streamed message.
- `Pager` now snaps every fetch range to a `loadSize` grid anchored at 0, instead of centring the first chunk on the
  accessed position (#10). Chunk starts used to depend on where the user landed, so a single jump issued overlapping
  requests (`490..509` and `480..499` for `key=500`, `loadSize=20`) and the same positions were fetched twice; the same
  data was also requested under different `(offset, limit)` pairs across passes, which no HTTP or CDN cache can reuse
  and which inflates the key space of offset-paged backends. `StreamingPager` already aligned its chunks this way. The
  cache window now also always covers the range the current load fetches, so alignment cannot push freshly requested
  items outside it.
- `Pager` now drives every load - key access, retry and refresh - from a single collector, so the scheduling state
  (in-flight job, planned range, last read key) is only ever touched from one coroutine.
- `Pager` prefetches the side the position is moving towards first (#8). The direction was computed and then discarded
  by a distance sort spanning both sides of the key, so the window filled symmetrically (`490, 510, 480, 530, ...`) and
  half the requests went to the side being scrolled away from before the leading side was covered. Each side is now
  ordered nearest-first on its own, and `Direction` is named after what it actually does.
- `Pager` no longer holds its mutex across the fetches, only across the cache transitions they produce, so a load can
  no longer be blocked by the one it supersedes.
- `PagingMediatorConfig.concurrency` now bounds the remote fetches of a whole pager rather than those of a single
  `readData` call: the chunks of one loading pass and the missing sub-ranges within each of them draw from one budget.
- `Pager`, `StreamingPagerConfig` and `PagingMediatorConfig` now reject a cache narrower than the preload window
  (`cacheSize >= preloadSize`, `cacheSize >= prefetchSize` for the mediator) with an `IllegalArgumentException` at
  construction time. Such a configuration used to be accepted silently while the pager streamed a window it could not
  retain — measured at ~76% of the payload discarded on arrival, with the streams that produced it left open (#13).
  `Pager` also validates `loadSize > 0` and `preloadSize >= 0`, which it did not check at all before.
- `StreamingPagerConfig` validates itself in its own `init` instead of in `StreamingPager`, so `copy()` is covered too.
- Bumped library versions: Kotlin to 2.4.10, kotlinx-coroutines to 1.11.0, Compose Multiplatform to 1.11.1,
  `androidx-activity-compose` to 1.13.0, `kotlinx-collections-immutable` to 0.5.1, and Kermit to 2.1.0.
- Bumped the BuildKonfig Gradle plugin to 0.22.0 (build-only, no effect on the published artifacts).
- `paging-samples` no longer builds the `iosX64` target — Compose Multiplatform 1.11 stopped publishing artifacts for
  it. The published `paging-core` library keeps `iosX64` and its full target set unchanged.
- Upgraded the Android Gradle Plugin to 9.3.1 (from 8.13.2) and the Gradle wrapper to 9.6.1 (from 9.2.1, the minimum
  AGP 9.3.1 accepts is 9.5.0). AGP 9 dropped Kotlin Multiplatform support from `com.android.library`, so both modules
  now apply `com.android.kotlin.multiplatform.library` and configure Android from inside the `kotlin { }` block
  instead of a top-level `android { }` one. Published coordinates are unchanged — the Android artifact is still
  `paging-core-android`; only the internal Gradle publication name changes (`androidRelease` -> `android`), which
  consumers do not match on.

### Documentation

- Clarified that `preloadSize`/`prefetchSize` and `cacheSize` are radii in indices around the current position, not
  item counts — `Pager`'s KDoc previously described `cacheSize` as the "maximum number of items to keep in memory",
  while the cache actually holds up to `2 * cacheSize` items. The invariant is now stated in the KDoc of all three
  configurations and in `README.md`.

### Fixed

- Pager: `PagingData.retry(key)` now actually retries. Retries used to be routed through the same conflating
  `StateFlow` that already held the failed key, so the documented recovery path (`retry(loadState.key)`, used by the
  README and by the sample's error overlay) emitted nothing and the pager stayed in `LoadState.Error` forever - only
  retrying with a *different* key recovered. Retries travel on their own channel now and are never debounced (#3).
- Pager: `refresh()` reloads instead of just emptying the list. It used to clear the cache and rely on the UI touching
  an item again, which the conflating key trigger then swallowed, leaving the list empty while reporting
  `LoadState.Success`. `refresh()` now cancels and awaits the in-flight load - which held a pre-refresh cache snapshot
  and could write cleared items back - clears the cache, and reloads the window around the position last accessed (#4).
- Pager: the initial load is no longer debounced. `keyTrigger` starts at `0`, so the first load waited out the full
  300 ms debounce despite having nothing to debounce - 75% of a 400 ms first paint against a 100 ms backend. Only
  subsequent position changes are debounced now (#7).
- StreamingPager: the pager no longer reports `LoadState.Loading` forever after the total shrinks. The out-of-bounds
  key clamp and the stream close filter were both off by one, which produced an empty chunk that was marked as loading
  but never opened, so the marker was never cleared (#5).
- StreamingPager: a portion stream covering exactly the new boundary index is now closed when the total shrinks,
  instead of being left open past the end of the list.

### Tests

- Added regression coverage for the shrink path in `StreamingPagerTest`, plus `WindowHelpersTest` pinning the invariant
  that the chunk planner never returns an empty range for an out-of-bounds key.
- `PagerTest` covers retry with the failed key, retry without waiting for the debounce, refresh reloading the window
  around the last accessed position, refresh racing an in-flight load, the undebounced initial load, and
  `keyDebounceMs` validation and effect. The F1/F5/F6 characterisation tests in `DiagnosticsFindingsTest` were flipped
  from documenting the defects to asserting the fixed behaviour.

## [2.2.7] - 2026-03-05

### Fixed

- IntRange.expandTo: corrected range expansion logic — when starting from key 0, the pager now correctly requests the
  full loadSize instead of a smaller range.

### Changed

- Bumped library versions: Kotlin to 2.3.10, AGP to 8.12.3.

### Tests

- Added regression test covering full loadSize request with initial key 0.

## [2.2.6] - 2026-01-21

### Fixed

- StreamingPager: refined initial load state logic to ensure `LoadState.Loading` is correctly emitted before the first
  data portion or total size is processed.
- StreamingPagerState: internal state now uses nullable tracking for range load states to explicitly represent the
  pre-initialization phase.

## [2.2.5] - 2026-01-20

### Fixed

- StreamingPager: set initial load state to `LoadState.Loading` on start for better consistency with UI lifecycle.

### Changed

- Migrated all tests to Kotest assertions for improved readability and maintenance.
- Added `kotest-assertions-core` dependency to `paging-core`.

## [2.2.4] - 2026-01-16

### Fixed

- StreamingPager: handle `readTotal()` failures by propagating `LoadState.Error` without terminating the flow.
- StreamingPager: retry now restarts `readTotal()` collection after a total-size failure.

### Changed

- Bumped library versions: `androidx-activity-compose` to 1.12.2, Kotlin to 2.3.0, AGP to 8.12.0, and Arrow to 2.2.1.1.

### Tests

- Added a regression test covering `readTotal()` error followed by retry recovery.

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
