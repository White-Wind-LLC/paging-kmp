# CI: testing code on pull requests

**Date:** 2026-08-01
**Status:** approved

## Problem

The repository has a single workflow — `publish.yml`, which triggers on `v*` tags and publishes
`paging-core` to Maven Central. Pull requests are not checked at all: broken tests or a target
that fails to compile are only discovered during release publication.

## Goal

Every PR to `main` automatically runs the tests and verifies that all `paging-core` targets
compile. The result is a single status check, suitable for branch protection.

## Scope

**In scope:**

- Running `commonTest` tests on JVM, Android, JS (Node), Wasm/JS (Node), linuxX64, macosArm64,
  iosSimulatorArm64.
- Compiling the remaining `paging-core` targets: linuxArm64, macosX64, iosX64, iosArm64.
- Publishing test reports: artifacts on failure + a summary and annotations on the PR.

**Out of scope:**

- Building the `paging-samples` module (it is run with `-PexcludeSamples=true`).
- `apiCheck` / binary-compatibility-validator.
- Linters (ktlint, detekt), code coverage.
- Windows runner.

## Architecture

A new file `.github/workflows/ci.yml` named `CI`, independent of `publish.yml`. Three jobs.

### Triggers

```yaml
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
```

Runs on `main` are not interrupted — cancellation of stale runs applies only to pull requests.

### Job `test-linux` (`ubuntu-latest`)

Runs two Gradle invocations: checking the lockfiles (see "Yarn lockfile") and
`./gradlew -PexcludeSamples=true check -x kotlinStoreYarnLock -x kotlinWasmStoreYarnLock`.
The second covers:

- `jvmTest`, `jsNodeTest`, `wasmJsNodeTest`, `linuxX64Test`, Android unit tests;
- compilation and linking of `linuxArm64` and `mingwX64`.

Steps:

1. `actions/checkout@v4`
2. `actions/setup-java@v4` — temurin, java 17
3. `gradle/actions/wrapper-validation@v4`
4. `gradle/actions/setup-gradle@v4`
5. `actions/cache@v4` for `~/.konan`
6. `chmod +x ./gradlew`
7. run `check`
8. reports (see below)

### Job `test-apple` (`macos-latest`)

The same preparation steps, but instead of `check` — an explicit list of tasks:

- tests: `:paging-core:macosArm64Test`, `:paging-core:iosSimulatorArm64Test`;
- compilation: `macosX64`, `iosX64`, `iosArm64` (`compileKotlin*` and `compileTestKotlin*`).

`macosX64Test` is not run: the `macos-latest` runner has arm64 architecture, and running
x86 binaries through Rosetta is slow and unreliable. The target is verified by compilation.

The job does not run jvm/js/wasm tests — they already ran on Linux, and duplicating them on a
more expensive runner makes no sense.

### Job `ci` (gate)

```yaml
needs: [test-linux, test-apple]
if: always()
```

Fails if any of the dependent jobs did not finish successfully. Provides a single stable status
check `ci` for branch protection — when the set of jobs changes, the branch protection settings
do not need to change.

## Gradle configuration in CI

`gradle.properties` is left unchanged — parameters are overridden via command-line flags, so as
not to affect local development.

**Memory.** The file sets `org.gradle.jvmargs=-Xmx6G` and `kotlin.daemon.jvmargs=-Xmx6G`. The
`macos-latest` runner has ~7 GB of RAM, which is not enough for two such processes. In CI we
pass:

- ubuntu: `-Dorg.gradle.jvmargs=-Xmx4g -Dkotlin.daemon.jvmargs=-Xmx4g`
- macOS: `-Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvmargs=-Xmx3g`

**Yarn lockfile.** `kotlin-js-store` is committed to the repository. CI does **not** run
`kotlinUpgradeYarnLock` (unlike `publish.yml`): if the lockfile has drifted from the
dependencies, the build fails, and the PR author updates the lockfile themselves. This is an
intentional check.

The check runs as a separate step **without** `-PexcludeSamples=true`:

```
./gradlew kotlinStoreYarnLock kotlinWasmStoreYarnLock
```

The reason became clear on the very first run. The committed lockfiles describe the dependency
graph including the `paging-samples` module, while `-PexcludeSamples=true` produces its strict
subset — so `kotlinStoreYarnLock` under that flag always reported a mismatch, regardless of
whether the lockfile was actually up to date. To keep both the lockfile check and the exclusion
of samples from the heavy build, the `check` step runs with
`-x kotlinStoreYarnLock -x kotlinWasmStoreYarnLock`. The `kotlinRestoreYarnLock` task stays in
the graph, so dependencies in CI are resolved exactly against the committed lockfile.

**Timeout.** `timeout-minutes: 45` for each job with tests.

## Caching

- `gradle/actions/setup-gradle@v4` caches the Gradle caches. The default behavior is suitable:
  the cache is written only from the default branch, PRs read it.
- `~/.konan` is cached separately via `actions/cache@v4` with a key based on the hash of
  `gradle/libs.versions.toml`. Without this, each job would download the Kotlin/Native toolchain
  again.

## Test reports

- `actions/upload-artifact@v4` with `if: failure()` — paths `**/build/reports/tests/**` and
  `**/build/test-results/**`, retention 7 days.
- `mikepenz/action-junit-report@v5` with `if: always()` — parses JUnit XML, writes a summary to
  the GitHub Job Summary, and adds annotations on the lines with failing tests in the pull
  request's diff.

Both steps are added to each of the `test-linux` and `test-apple` jobs; artifact names differ
per job.

## Known gaps

**`mingwX64`.** Verified on a real run: the Linux host fully cross-compiles the Windows target —
`compileKotlinMingwX64`, `compileTestKotlinMingwX64`, and even `linkDebugTestMingwX64` all run,
meaning the test binary is linked. Only `mingwX64Test` is skipped (`SKIPPED`) — running the tests
requires a Windows host.

So the gap is narrower than expected: for `mingwX64`, only the test execution itself does not
happen, while compilation and linking are covered by the `test-linux` job. A Windows runner is
deliberately not added (a conscious decision about cost and run time). The decision will be
revisited if a bug is ever found on this target that can only be caught by running the tests.

## Manual steps after merge

In Settings → Branches for the `main` branch, enable the required status check `ci`. This is not
configurable via files in the repository.

## Documentation

Add a CI status badge to the top of `README.md`.
