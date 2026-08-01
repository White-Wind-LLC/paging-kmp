# The `cacheSize >= preloadSize` invariant

**Date:** 2026-08-01
**Status:** approved
**Issue:** [#13](https://github.com/White-Wind-LLC/paging-kmp/issues/13)

## Problem

Pager configuration is validated for sign only: `loadSize > 0`, `preloadSize >= 0`,
`cacheSize >= 0`. Nothing checks `cacheSize` against `preloadSize`, even though the two are tightly
coupled: a pager loads data within a window of radius `preloadSize` around the last accessed key,
but retains in memory only a window of radius `cacheSize` around that same key.

When `cacheSize < preloadSize`, the pager opens streams for a window it cannot hold.
`StreamingPagerState.onPortion` prunes every arriving portion against `cacheRange` and drops most of
it immediately, while the streams stay open and keep pushing data that is discarded the same way.
There is no error, no warning and no log.

Measured at `loadSize=20, preloadSize=100, cacheSize=40` with a jump to position 500:

- 340 items pulled over the wire
- 81 items retained
- ~76% of the payload discarded on arrival
- 11 portion streams left open, pushing data into the bin forever

The same relationship exists in `Pager` (`Pager.kt:228` against `Pager.kt:330`) and, transitively,
in `PagingMediatorConfig`, which forwards `prefetchSize`/`cacheSize` to `Pager`.

The KDoc is misleading on top of that: `Pager` says "Maximum number of items to keep in memory
(default: 100)", while `cacheSize` is a radius and the cache actually holds up to `2 * cacheSize`
items. The wording in `StreamingPagerConfig` — "Cache radius in indices around the last accessed
key" — is not wrong, but gives no hint that the radius has to be at least the preload radius.

## Goals

1. An invalid `cacheSize`/`preloadSize` ratio surfaces immediately when the pager is constructed,
   instead of silently burning bandwidth.
2. The error message explains the relationship between the parameters rather than just stating a
   failed condition.
3. The documentation describes both parameters as radii and states the invariant.

## Scope

**In scope:**

- `require(cacheSize >= preloadSize)` in `StreamingPagerConfig`, `Pager` and `PagingMediatorConfig`.
- Moving the existing `require`s from `StreamingPager.init` into the `init` of
  `StreamingPagerConfig` itself.
- A full set of `require`s in `Pager`, which currently has no validation at all.
- KDoc corrections for `cacheSize`/`preloadSize`/`prefetchSize` in three places, plus the matching
  comments in `README.md`.
- Bringing the project's own tests in line with the invariant, and rewriting the F8 repro test as a
  regression test.
- New validation tests for all three configurations.
- A `CHANGELOG.md` entry.

**Out of scope:**

- Clamping `preloadSize` down to `cacheSize` — deliberately rejected in favour of failing fast.
- A logger in `Pager`/`PagingMediator` — only needed for the clamping variant.
- Changing the chunk grid in `StreamingPager`. The residual waste at the edges of the grid is
  documented but not fixed here.
- Validating other fields (`concurrency`, or `closeThreshold` beyond its existing sign check).

## Decisions

### Why fail-fast rather than clamping

`cacheSize < preloadSize` is a configuration error with no sensible runtime fallback. Silent
clamping would hand the caller a smaller preload than they asked for and hide the cause. An
exception at construction points at the exact line of configuration that is wrong.

This is behaviourally breaking for applications running a currently-invalid configuration: they get
a crash instead of silent degradation. Those applications are already burning bandwidth and
battery, so the change makes an existing defect visible rather than introducing a new one. It
warrants a minor release, not a patch.

### Why `cacheSize >= preloadSize` specifically

In `StreamingPager` the actual stream window is aligned to the `loadSize` grid and therefore reaches
past the preload radius: the centre chunk containing the key is expanded by `preloadSize` in each
direction, and the window is then tiled with chunks, the last of which may extend beyond the window
edge. The exact condition for "no loaded item is discarded on arrival" is
`cacheSize >= preloadSize + 2*loadSize - 2`.

That formula is a poor fit for a `require`: it is hard to explain, it is tied to an implementation
detail of the chunk planner, and it would reject configurations that lose a couple of percent.
Therefore:

- the `require` enforces the plain `cacheSize >= preloadSize`, which removes the catastrophic case;
- the KDoc recommends `cacheSize >= preloadSize + loadSize` for `StreamingPager` to also remove the
  residual waste at the grid edges.

The library defaults (`loadSize=20, preloadSize=60, cacheSize=100`) satisfy both conditions.

### Where to validate

| Location | Checks |
|---|---|
| `StreamingPagerConfig.init` | `loadSize > 0`, `preloadSize >= 0`, `closeThreshold >= 0`, `keyDebounceMs >= 0`, `cacheSize >= preloadSize` |
| `Pager.init` | `loadSize > 0`, `preloadSize >= 0`, `cacheSize >= preloadSize` |
| `PagingMediatorConfig.init` | `loadSize > 0`, `prefetchSize >= 0`, `cacheSize >= prefetchSize` |

`cacheSize >= preloadSize` together with `preloadSize >= 0` implies `cacheSize >= 0`, so the
separate sign check on `cacheSize` becomes redundant and is dropped.

Validation of `StreamingPagerConfig` moves out of `StreamingPager.init` and into the `init` of the
data class itself: `init` also runs for `copy()`, so it catches cases where a configuration is
mutated after creation and passed somewhere else. Once moved, `StreamingPager.init` is empty and is
removed — leaving a single source of truth.

`PagingMediatorConfig` validates on its own even though it ultimately delegates to `Pager`: the
parameter is called `prefetchSize` there, and the message has to name what the user sees in their
own code.

### Error message

One shared format across all three locations, carrying the actual values and explaining the link:

```
cacheSize (40) must be >= preloadSize (100): the pager retains only +/-cacheSize items around
the last accessed key, so a wider preload radius fetches data that is discarded on arrival
```

`PagingMediatorConfig` uses the same text with `prefetchSize` in place of `preloadSize`.

### Documentation

`cacheSize` and `preloadSize` are documented as **radii in indices** around the last accessed key,
stating explicitly that the cache holds up to `2 * cacheSize + 1` items (`2 * cacheSize` in `Pager`,
whose cache range has an exclusive end). The KDoc of each of the three configurations states the
invariant; the KDoc of `StreamingPagerConfig` additionally carries the
`cacheSize >= preloadSize + loadSize` recommendation.

In `README.md`, the descriptive comments next to the configuration examples are corrected (around
lines 86–87, 239–240, 295–296), where `cacheSize` is called "max items retained in memory".

## Impact on existing tests

Two places in the project's own code violate the new invariant:

1. **`PagerTest.moving_far_evicts_outside_cache_range`** (`cacheSize=40, preloadSize=60`) moves to
   `cacheSize=60, preloadSize=60`. Its assertions are unchanged and remain valid: they check that
   the keys lie within `400 ± preloadSize`, and `cacheRange` now coincides with the preload window.

2. **`DiagnosticsFindingsTest.f8_cache_smaller_than_preload_streams_data_that_is_discarded`** is the
   repro from the issue and pins the broken behaviour with `fetched=340, retained=81`. It is
   rewritten in the "(fixed)" style already used for F9: the `20/100/40` configuration now throws
   `IllegalArgumentException`, and a companion case checks a valid `20/100/120` configuration where
   every item streamed for the viewport is retained. The concrete numbers are taken from a real run
   during implementation.

The rest of the tests and the samples satisfy the invariant already: `StreamingPagerTest` always has
`cacheSize >= preloadSize` (tightest is `preloadSize=5, cacheSize=10`), `WindowHelpersTest` uses
`20/20/200`, and the `StreamingUserListViewModel` sample uses the `20/60/100` defaults.

## New tests

For each of the three configurations:

- an invalid ratio throws `IllegalArgumentException`, and the message contains both parameter names
  along with their values;
- the boundary case `cacheSize == preloadSize` constructs successfully.

For `StreamingPagerConfig` additionally: a `copy(cacheSize = ...)` that breaks the invariant throws
too — which is the reason validation moved into the data class.

## Definition of done

1. `./gradlew check` passes, including detekt and Spotless.
2. The configuration from the issue (`20/100/40`) throws `IllegalArgumentException` for all three
   pagers.
3. Default configurations construct with no behavioural change.
4. The KDoc of all three `cacheSize` parameters describes a radius and states the invariant;
   `README.md` agrees.
5. `CHANGELOG.md` carries an entry under `[Unreleased] / ### Changed` referencing #13.
