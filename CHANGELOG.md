# Changelog

## 1.0.0-rc3 - 2026-09-02

- Documented the precise Java-to-native ABI contract, including portable handling for Java `long`.
- Added release workflow gating across Ubuntu, macOS, and Windows before GitHub release artifact creation.
- Added Maven Central Portal publication metadata/profile and attached sources/JavaDoc artifacts for public modules.
- Strengthened consumer-style Maven Invoker validation to invoke both scalar and array native functions.
- Added NativeArray lifecycle/thread-confinement tests.
- Updated the Central Portal publishing plugin and explicitly skipped non-published modules in Central publication.

## 1.0.0-rc2 - 2026-07-08

- Added CI coverage checks for Ubuntu and macOS, plus a Windows smoke path for core modules.
- Added release automation that builds source and jar artifacts from version tags.
- Added contributor-facing project files, including security, code of conduct, contribution, release, and testing documentation.
- Verified native build diagnostics, expected-symbol validation, JavaDoc generation, and benchmark packaging for the release candidate.
