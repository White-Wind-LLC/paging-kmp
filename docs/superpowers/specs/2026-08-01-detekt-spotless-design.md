# Static analysis: detekt and Spotless

**Date:** 2026-08-01
**Status:** approved

## Problem

The project has no static analysis tooling at all. Code style relies on the author's discipline
and IDE settings, and the "Contributing" section in `README.md` asks to "keep the code style
consistent" without explaining what that means or how to check it. There is no `.editorconfig`,
no linter, no build-level check. Formatting discrepancies surface in code review instead of being
fixed automatically.

## Goal

1. Kotlin code formatting is checked and fixed automatically, with a single command.
2. Common defects (complexity, potential bugs, style) are caught by a linter before merge.
3. Both fail locally on `./gradlew check` and via a separate fast check in CI.

## Scope

**In scope:**

- The Spotless plugin with the ktlint formatter — **only for `*.kt` files**.
- The detekt 2.0.0-alpha.5 plugin (coordinates `dev.detekt`) with the default ruleset and a
  small file of targeted suppressions.
- An `.editorconfig` file at the root as the source of rules for ktlint and the IDE.
- A new `static-analysis` job in the existing `.github/workflows/ci.yml`.
- A one-time reformatting of the existing code and fixes for the findings discovered.
- A `.git-blame-ignore-revs` file with the reformatting commit.
- An update to the "Contributing" section in `README.md`.

**Out of scope:**

- Spotless for `*.gradle.kts`, `*.md`, `*.yaml`, `*.toml` — deliberately, as a scope decision.
- The `detekt-rules-ktlint-wrapper` ruleset — formatting is handled exclusively by Spotless.
- Running detekt as a compiler plugin (`enableCompilerPlugin`) and, consequently, type
  resolution.
- `detekt-baseline.xml` — all findings are addressed immediately.
- A local pre-commit hook.
- Code coverage, binary-compatibility-validator.

## Division of responsibility

The two tools do not overlap, and that is not a coincidence but a configuration constraint:

| Tool | Responsible for | Auto-fixes |
|---|---|---|
| Spotless + ktlint | text layout: indentation, line breaks, whitespace, import order | yes, `spotlessApply` |
| detekt | code meaning: complexity, potential bugs, naming, dead code | no |

In detekt 2, the formatting rules are split out into a separate artifact,
`dev.detekt:detekt-rules-ktlint-wrapper`, which is published independently and is not part of
the plugin's default dependency. We do not add it — so overlap between ktlint rules from both
sides is impossible by construction, with no manual exclusions needed in the config.

## Architecture

### Version catalog

`gradle/libs.versions.toml`:

```toml
[versions]
detekt = "2.0.0-alpha.5"
spotless = "8.9.0"

[plugins]
detekt = { id = "dev.detekt", version.ref = "detekt" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

The ktlint version is not pinned separately: Spotless 8.9.0 ships ktlint 1.8.0 and is invoked as
`ktlint()` with no arguments. Updating ktlint happens together with updating Spotless — one
variable instead of two that would need to be kept compatible.

### Wiring at the root

Both plugins are declared in the root `build.gradle.kts` with `apply false` and are applied
through the existing `allprojects {}` block — the same way Dokka is already applied there:

```kotlin
import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension

plugins {
    // ... existing plugins
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
}

allprojects {
    // ... existing configuration
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "dev.detekt")

    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**")
            ktlint()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
        basePath = rootDir
    }
}
```

Configuration is done via `extensions.configure<T>()` rather than `spotless { }` /
`detekt { }` blocks: typed Gradle accessors are only generated for plugins applied within the
`plugins {}` block of that same script, whereas here the plugins are applied dynamically inside
`allprojects`. The extension classes are still importable because `apply false` puts the plugin
on the build classpath regardless.

The root project also receives both plugins. It has no `src` directory, so `target("src/**")`
yields an empty set and the `:spotlessCheck` and `:detekt` tasks run as no-ops. This is an
acceptable price for having a single point of configuration: a third module added in the future
is automatically covered by the analysis.

No separate wiring to `check` is needed — both plugins hook their own `*Check` tasks
automatically.

### `.editorconfig`

A new file at the root of the repository. This is exactly where ktlint reads its rules from;
without it, the formatter falls back to its own defaults, which can diverge from the IDE.

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
indent_style = space
indent_size = 4
insert_final_newline = true
trim_trailing_whitespace = true

[*.{kt,kts}]
ktlint_code_style = intellij_idea
max_line_length = 120

[*.{yml,yaml,json,toml}]
indent_size = 2
```

The key decision is `ktlint_code_style = intellij_idea`, not `ktlint_official`.
`gradle.properties` already has `kotlin.code.style=official`, meaning the IDE formats code in
JetBrains's style. `ktlint_official` is ktlint's own, noticeably stricter style (in particular,
different parameter-wrapping rules and mandatory trailing commas). By choosing `intellij_idea`,
we guarantee that `Cmd+Alt+L` in the IDE and `spotlessApply` produce the same result; otherwise a
developer who formatted a file using the IDE would get a failing `spotlessCheck`.

### `config/detekt/detekt.yml`

The file contains **only** deviations from the default (`buildUponDefaultConfig = true`), not a
copy of the full ~800-line config. This is deliberate: a copy of the defaults goes stale with
every detekt update and hides what we actually changed intentionally.

Exactly two kinds of entries are allowed:

1. **A disabled or relaxed rule** — always with a comment explaining the reason.
2. **Exclusion of test paths.** detekt's default exclusions target patterns like `**/test/**`,
   whereas in the KMP module the paths look like
   `paging-core/src/commonTest/kotlin/…`. Test patterns (`**/commonTest/**`, `**/jvmTest/**`,
   `**/androidUnitTest/**`) are factored into a YAML anchor and reused by the rules that are
   noisy on tests.

The specific list of entries is filled in during implementation based on the first run's
results. The decision rule is fixed here and leaves no room for "let it stay noisy for now":
every finding is either fixed in the code or suppressed in `detekt.yml` with a written
justification.

### CI

A third job is added to the existing `.github/workflows/ci.yml`, alongside `test-linux` and
`test-apple`:

```yaml
  static-analysis:
    name: Static analysis
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Tune Gradle memory for CI runner
        run: |
          mkdir -p ~/.gradle
          {
            echo "org.gradle.jvmargs=-Xmx4g"
            echo "kotlin.daemon.jvmargs=-Xmx4g"
          } >> ~/.gradle/gradle.properties

      - name: Make gradlew executable
        run: chmod +x ./gradlew

      - name: Run Spotless and detekt
        run: ./gradlew spotlessCheck detekt

      - name: Upload analysis reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: static-analysis-reports
          path: '**/build/reports/detekt/**'
          retention-days: 7
```

Three decisions in this job are worth explaining.

**No `~/.konan` cache.** The standalone detekt task does not compile code, so the Kotlin/Native
toolchain is not needed. This is exactly why the job is fast: seconds to minutes versus well
over forty minutes in the test jobs, meaning formatting feedback arrives well before test
results.

**No `-PexcludeSamples=true`**, unlike `test-linux`. The `paging-samples` module is also our
code, and excluding it from analysis would leave a third of the repository unchecked. There is
no heavy build here, and the Android SDK is preinstalled on `ubuntu-latest`, so the module's
configuration succeeds.

**The job is added to the `needs` of the `ci` gate**, together with a check of its result:

```yaml
  ci:
    needs: [static-analysis, test-linux, test-apple]
    # ...
    [ "${{ needs.static-analysis.result }}" = "success" ] || exit 1
```

Thanks to this, the branch protection settings do not need to change: the required status check
`ci` is already configured and now starts taking static analysis into account.

**Change to the existing `test-linux` job.** To avoid running the same tasks twice, the
`Run checks` step is extended with exclusions:

```
./gradlew -PexcludeSamples=true check \
  -x kotlinStoreYarnLock -x kotlinWasmStoreYarnLock \
  -x spotlessCheck -x detekt
```

## Acceptance criteria

1. `./gradlew spotlessCheck detekt` succeeds on a clean tree.
2. `./gradlew check` without extra flags runs both tasks (verified via `--dry-run`).
3. **Source set coverage.** In KMP, detekt registers both an aggregate task and per-compilation
   tasks; what exactly `./gradlew detekt` in 2.0.0-alpha.5 looks at is verified empirically: a
   temporary violation is introduced into `paging-core/src/commonMain` and
   `paging-samples/src/iosMain`, and both must be found. If the aggregate task does not cover
   everything, the needed tasks are explicitly appended to `check`.
4. **Configuration cache.** The project has `org.gradle.configuration-cache=true` set. Two
   consecutive runs of `./gradlew spotlessCheck detekt`, and the second one must report
   `Reusing configuration cache`.
5. **Formatter idempotence.** Running `./gradlew spotlessApply` again after the first run
   produces no changes in the working tree.
6. **Consistency with the IDE.** Formatting an arbitrary file with the IDE (`Cmd+Alt+L`) does
   not create a discrepancy with `spotlessCheck`.
7. The `static-analysis` job is green on the pull request, and the `ci` gate takes its result
   into account.

## Rollout plan

Three separate commits — the order matters:

1. **Infrastructure.** `libs.versions.toml`, the root `build.gradle.kts`, `.editorconfig`,
   `config/detekt/detekt.yml`, `ci.yml`. The build is still red after this commit — that is
   expected.
2. **`spotlessApply`.** A bulk reformatting of ~45 files and **nothing else**: no logic changes,
   no config changes.
3. **detekt fixes.** Code fixes plus the final entries in `detekt.yml`.

The second commit is kept separate specifically so its SHA can be added to a new
`.git-blame-ignore-revs` file — otherwise `git blame` across the whole library would start
pointing at the reformatting instead of the authors of the actual changes. The file is picked up
automatically by GitHub; for local `git blame`, a command is added to the README:
`git config blame.ignoreRevsFile .git-blame-ignore-revs`.

## Risks

**detekt 2.0.0-alpha.5 is an alpha.** This is a conscious choice: the 1.23.x branch is no longer
developed and has known issues with Gradle 9 (the project is on Gradle 9.2.1), while 2.x targets
it. The risk will surface on the very first local run, at the latest. The rollback is a two-line
change in the version catalog to `io.gitlab.arturbosch.detekt` 1.23.8 plus a change to the plugin
id; it is not free, and if 1.23.8 does not work on Gradle 9 either, the remaining options are to
temporarily wire detekt only into `paging-core`, or to postpone detekt and keep only Spotless.

**Configuration cache.** Spotless 8.x fully supports it as of version 7.0. For the alpha version
of detekt, this is not guaranteed. If the cache breaks, the `static-analysis` job is temporarily
run with `--no-configuration-cache`; this isolates the problem to CI without affecting the rest
of the build.

**Scope of reformatting.** 45 files in the second commit is a large diff. This is mitigated by
the fact that it is isolated and captured in `.git-blame-ignore-revs`.

## Documentation

The "Contributing" section in `README.md` currently asks to "keep the code style consistent"
without explaining how. It is replaced with specifics: `./gradlew spotlessApply` before
committing, `./gradlew spotlessCheck detekt` to check, a mention of `.editorconfig` as the
source of the rules, and a line about `blame.ignoreRevsFile`.

## Manual steps after merge

None. The `ci` gate is already configured as a required status check and automatically starts
taking the new job into account.
