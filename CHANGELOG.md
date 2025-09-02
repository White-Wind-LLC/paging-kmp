# Changelog

All notable changes to this project will be documented in this file.

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
