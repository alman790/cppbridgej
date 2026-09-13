# Changelog

## 1.1.0 - 2026-09-13

- Add record-based struct values, nested layouts, fixed inline arrays, and owned struct buffers.
- Add UTF-8 strings, explicit int32 enums, pointers, and boolean/short/char scalars and buffers.
- Add temporary and retained callbacks with contained Java failures and native-thread support.
- Add C++ status-to-exception mapping, a bundled guard header, and owned opaque handles.
- Add explicit FFM downcalls for unions, custom layouts, and C variadic functions.
- Add an executable C++ class/STL example and cross-platform ABI and lifecycle tests.
- Compile MSVC sources as UTF-8.

## 1.0.0 - 2026-09-12

- Package native libraries in JARs and resolve them through the API class loader when the development binary is absent.
- Preserve native pointer identity for repeated heap-array arguments and combine their copy directions.
- Keep managed arrays usable and closable after a failed close from another thread.
- Share signature validation between loading and inspection; handle redeclared Object methods and package-private default methods correctly.
- Release inspection lookups after producing a report and add context to library-loading failures.
- Add include directories, linker arguments, command timeouts, and command-line properties to the Maven plugin.
- Include Maven Wrapper, validate the build JDK, and provide a complete standalone quickstart.
- Test a packaged consumer in a separate JVM launched outside the build directory.

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
