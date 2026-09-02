# Release Readiness

## Must pass before v1.0.0

- Public API review confirms the stable 1.0 surface: annotations, `CppBridge`, diagnostics records/enums, managed native arrays, and the Maven `compile-cpp` goal.
- `docs/ABI_CONTRACT.md` matches runtime descriptor generation and examples.
- `mvn -B clean verify` passes locally.
- `mvn -B -Pcoverage clean verify` passes locally.
- The example script compiles, loads, and invokes the native library.
- Maven Invoker integration tests pass, including the isolated consumer smoke project.
- JavaDoc JARs and sources JARs are produced for `cppbridge-core` and `cppbridge-maven-plugin`.
- GitHub Actions release verification passes on Ubuntu, macOS, and Windows.
- Windows verification proves MSVC `cl`, `dumpbin /EXPORTS`, DLL compilation, symbol validation, Java FFM loading, and native invocation.
- Maven Central credentials and signing keys are configured outside the repository.

## Post-1.0 roadmap

- strings;
- structs and custom memory layouts;
- callbacks;
- native exception transport;
- Gradle plugin;
- WASM backend;
- optional explicit library unloading;
- broader architecture matrix beyond hosted x64 runners.

These roadmap items should not block a stable 1.0 release of the current primitive-scalar and primitive-array ABI.
