# Split load state: the accessed position vs background prefetch

**Date:** 2026-10-20
**Status:** draft
**Issue:** [#11](https://github.com/White-Wind-LLC/paging-kmp/issues/11)

## Why

`PagingData.loadState` is one global flag, and it answers "is the pager doing anything at all". UI code
written the way the README shows (`LoadState.Loading -> showLoader()`) needs a different answer: "is the
data the user is looking at missing". The two diverge all the time:

- **`Pager`** sets `Loading` before a pass and clears it only after every chunk of the preload window
  and, with `itemKey`, the key-conflict round have finished (`Pager.runLoads`). After a jump the visible
  chunk lands first, yet the spinner stays up until the whole window is filled.
- **`StreamingPager`** reports `Loading` while *any* range is loading (`StreamingPagerState.loadStateFlow`).
  Opening a preload stream at the edge of the window flips it, even when every visible item is cached:
  a small scroll over cached data produces `Success -> Loading -> Success`
  (`DiagnosticsFindingsTest.f7_streaming_loadstate_flaps_on_scroll_over_cached_data`).

This is library housekeeping: there is no specific app incident behind it. The maintainer accepts breaking
the API to get the model right.

## Goals

1. `loadState` is `Loading` only while the item at the accessed position is missing and being loaded.
   Scrolling over cached data never changes it.
2. Background work (the rest of the preload window, the key-conflict round) is still observable, through a
   separate `prefetchState`.
3. A background failure does not block the UI; it surfaces in `loadState` only once the user actually
   reaches the failed data.
4. Both pagers follow the same rule, so the same UI code works for either.
5. The README pattern `LoadState.Loading -> showLoader()` becomes correct without changes on the consumer
   side.

## Non-goals

- Paging 3 style `refresh` / `prepend` / `append` states. The pagers are random-access (jumps, scrollbar
  drags); "prepend" and "append" have no meaning for a jump, while "where the user is" vs "around it" does.
- Viewport awareness. The pagers only know the last accessed position; that stays the proxy for "what the
  user is looking at".
- Per-item errors (`EntryState.Error`). Out of scope.
- Changing the debounce. While the debounce (`keyDebounceMs`) runs after scrolling into an uncached area,
  `loadState` stays `Success` and the rows show `EntryState.Loading` - same as today.

## Anti-goals

- A spinner that flashes on ordinary scrolling over cached data, with either pager.
- A prefetch failure that turns the whole list into an error screen while the visible data is fine.
- A fix that leaves the two pagers with different meanings for the same field.

## Design

### Public API

```kotlin
public data class PagingData<T>(
    val data: PagingMap<T>,
    val loadState: LoadState,      // the accessed position
    val prefetchState: LoadState,  // background fill of the rest of the window
    val retry: (key: Int) -> Unit,
)
```

- `LoadState` itself (`Loading` / `Success` / `Error(throwable, key)`) is unchanged.
- `PagingData.empty()` has both states `Success`. `PagingData.map` carries both through.
- KDoc of `LoadState`, `PagingData`, and the README are rewritten to describe the two meanings.
- This is a breaking change (constructor, `copy`, `componentN`). It goes into the unreleased **2.4.0**,
  which is already a breaking release (`PagingMap` stopped being a data class), under
  "Changed - **Breaking:**" in the CHANGELOG.

### The rule, for both pagers

The **accessed position** is the key a load is planned for (after debounce / retry / refresh), the same
key each pager already plans its window around. Its **anchor chunk** is the grid cell / stream range that
contains it.

`loadState`:
- `Loading` - the item at the accessed position is not in the cache (or is hidden by a key conflict) and a
  fetch covering it is in flight. This covers the first load, `refresh()`, a jump into an uncached area,
  and a keyed conflict round that refetches the anchor cell.
- `Error(t, key)` - loading the anchor chunk failed, including when its flow throws after it already
  emitted. The consumer can tell "error over visible (possibly stale) data" from "error over nothing" by
  looking at `data`.
- `Success` - otherwise. In particular, as soon as a portion containing the accessed position has been
  merged, even if the anchor flow keeps emitting and the rest of the window is still loading.

The test is always **the item at the accessed position**, never "the whole grid cell": a cell can have
missing positions while the item at the key is cached, and then `loadState` is `Success`.

*Decision (builder check):* `readData` may emit several portions per chunk (`PagingMediator` emits the
local portion first, then remote ones). The first emission that puts the accessed position in the cache
ends `Loading`; an empty or partial local portion that does not contain it does not. Stale local data at
the key counts as data.

`prefetchState`:
- Covers every other chunk of the window, plus the key-conflict round of a keyed `Pager` pass.
- Aggregated with the existing priority `Loading > Error > Success`.
- A failure here does not touch `loadState`. When the user later reaches that chunk, it becomes the
  anchor of the next pass and its failure (if it repeats) shows up in `loadState`.

`Error.key` in both states is the **current** accessed position (the key the pager last planned for), i.e.
the key `retry` should be called with. `retry(key)` then re-plans the window where the user is and fetches
only what is still missing, the failed chunk included, if it is still inside the window.

*Decision (builder check):* an error can outlive scrolling (a failed edge stream keeps its entry in
`rangeLoadStates`; a `Pager` prefetch error stays until a pass fetches something). Its key must not pin the
window to where the failure happened, so it is resolved against the current accessed position when the
state is published, not frozen at failure time. Streaming keeps storing the throwable per range and fills
in the key in the derived flow; `Pager` rewrites the key of a lingering `Error` whenever it plans a new
pass. (Today streaming range errors carry `range.first`; this changes.)

### `Pager`

- The anchor chunk is the grid cell holding the key. When the item at the key is already cached,
  `loadState` goes to `Success` at the start of the pass and only `prefetchState` goes `Loading`.
- `loadState` goes `Success` when a merged portion puts the item at the key in the cache, not at the end of
  the pass.
- *Decision (builder check):* `loadState` follows the user into a running pass. When a debounced access
  lands inside `currentLoadingRange` (the early return in `keysJob`), the pass is not replaced, but the
  anchor moves to the new key: if the item there is missing, `loadState` goes `Loading` until a portion
  covering it is merged, and its failure goes to `loadState`. Moving that chunk to the front of the
  pending fetches is welcome but optional. This keeps `Pager` consistent with `StreamingPager`, which
  re-plans on every debounced key.
- `prefetchState` goes `Success` when the remaining chunks and the conflict round have finished.
- A failing prefetch chunk must not cancel the anchor chunk, and must not turn into `loadState = Error`.
  Today one failure in `fetchChunks` (`coroutineScope` + `awaitAll`) cancels its siblings and the whole
  pass reports `LoadState.Error`; that has to change at least for the anchor.
- A failing anchor chunk sets `loadState = Error`. Whether the prefetch chunks keep going is left to the
  builder.
- A pass with nothing to fetch keeps today's behaviour for `loadState`: a `Loading` left behind by a
  cancelled pass is settled to `Success`; an `Error` is left for the consumer to retry.
- *Decision (final review):* a `prefetchState = Error` whose failed chunk is no longer planned - it is
  cached by now, or it fell out of the window - settles to `Success` as soon as a pass plans no prefetch
  work. A failed chunk still in the window and still missing is planned again by the next pass, so the
  error cannot be lost; without this rule a lingering error re-keyed to the current position made
  `retry(error.key)` a no-op.
- Initial values: `loadState = Loading` (unchanged), `prefetchState = Success` until the first pass
  schedules prefetch work.

### `StreamingPager`

- The anchor range is the planned range containing `lastReadKey`.
- `loadState` is derived from the anchor range's state and the cache: `Loading` only when that range is
  `Loading` *and* the item at `lastReadKey` is not cached. Scrolling back into cached data whose stream had
  been closed and is now reopened therefore stays `Success`.
- `prefetchState` is the aggregate over all other ranges in `rangeLoadStates`.
- *Decision (final review):* an `Error` entry of a range whose stream has ended is dropped once the range
  lies further than `closeThreshold` outside the window, like an active stream would be closed. Same
  lifetime as a `Pager` prefetch error: it lives while the failed data is still wanted.
- A `readTotal()` failure (`markKnownRanges`) affects the whole list, so it shows up in `loadState`. The
  anchor range is among the known ranges, so this falls out of the rule; when nothing is known yet the
  first page stands in, as today.
- Both derived flows keep `distinctUntilChanged`, so streamed messages do not re-emit unchanged states.

### `PagingMediator`

It wraps a pager and forwards `PagingData`; it gets `prefetchState` for free. The builder checks that
nothing in it constructs `PagingData` by hand.

### Samples, README, CHANGELOG

- Samples are updated to compile; the top bar keeps showing its indicator on `loadState == Loading`.
- README: the state table and both `when (loadState)` snippets explain `loadState` vs `prefetchState`.
- CHANGELOG 2.4.0: a "**Breaking:**" entry for the new field, and the behaviour change of `loadState`
  (#11).

### Tests

- `f7` flips: a small scroll over cached data leaves `loadState` at `Success` with no transitions.
- `Pager`: after a jump, `loadState` becomes `Success` once the anchor chunk lands while `prefetchState`
  is still `Loading`; a failing prefetch chunk leaves `loadState` at `Success` and sets
  `prefetchState = Error`; a failing anchor chunk sets `loadState = Error`; `retry(error.key)` recovers
  both.
- `StreamingPager`: a jump into an uncached area goes `Loading -> Success` on `loadState` once the anchor
  range emits; opening edge streams changes only `prefetchState`; a failing edge stream sets only
  `prefetchState = Error`.
- Existing tests that assert `loadState` transitions are adjusted to the new meaning.

## Left to the builder

- How `fetchChunks` / `runLoads` are restructured to isolate the anchor chunk from prefetch failures.
- Whether prefetch chunks continue after an anchor failure, or after another prefetch failure.
- Internal naming and how the two streaming states are derived from `rangeLoadStates`.
- Exact test names and where they live.
