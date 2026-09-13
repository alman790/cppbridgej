# Release Readiness

## GitHub release gates

- Public API review confirms the stable 1.0 surface: annotations, `CppBridge`, diagnostics records/enums, managed native arrays, and the Maven `compile-cpp` goal.
- `docs/ABI_CONTRACT.md` matches runtime descriptor generation and examples.
- `mvn -B clean verify` passes locally.
- `mvn -B -Pcoverage clean verify` passes locally.
- The example script compiles, loads, and invokes the native library.
- Maven Invoker integration tests pass, including the isolated consumer smoke project.
- JavaDoc JARs and sources JARs are produced for `cppbridge-core` and `cppbridge-maven-plugin`.
- GitHub Actions release verification passes on Ubuntu, macOS, and Windows.
- Windows verification proves MSVC `cl`, `dumpbin /EXPORTS`, DLL compilation, symbol validation, Java FFM loading, and native invocation.
## Maven Central gate (pending)

The original release checklist also requires Maven Central credentials and signing keys configured outside the repository. That gate is not satisfied by the GitHub release: Central publication remains pending, and the quickstart explicitly installs the GitHub Maven bundle. Before announcing Central availability, configure namespace access and signing, deploy, and verify a fresh consumer against Central.

## Further work

Header-based wrapper generation, additional CPU architectures, Gradle integration and explicit library unloading remain future work. Strings, records, callbacks and C++ exception guards are covered by the 1.1 release checks.
