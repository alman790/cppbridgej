# Release Checklist

## Must pass before v1.0.0

### Local checks

```bash
mvn clean verify
mvn -Pcoverage clean verify
./scripts/run-example.sh
./scripts/show-build-reports.sh
```

### Consumer smoke

Confirm the Maven Invoker `consumer-load` integration test passes. It must resolve packaged artifacts through the isolated Invoker repository, compile a native library, inspect expected symbols, load the library, and call scalar and array functions.

### Supported-platform CI

- Ubuntu release verification passes.
- macOS release verification passes.
- Windows release verification passes with MSVC, `cl`, `dumpbin`, native compiler-backed tests, and the native example.

Do not cut `v1.0.0` from a run where any supported-platform job failed or was skipped unexpectedly.

### Benchmark check

Run at least one benchmark group before editing benchmark documentation:

```bash
./scripts/run-image-benchmarks.sh
./scripts/run-pipeline-benchmarks.sh
```

Record JVM, compiler, OS, and command line.

### Documentation

- README commands match the current scripts.
- `docs/QUICKSTART.md` works from a clean checkout.
- `docs/ABI_CONTRACT.md` matches the runtime mapper and examples.
- Benchmark numbers are labelled with environment information.
- `docs/ROADMAP.md` separates implemented and planned features.
- `CHANGELOG.md` has an entry for the release.

### Repository

- CI passes on Ubuntu, macOS, and Windows.
- License file is present.
- `.gitignore` excludes generated output.
- Source archive excludes `target/` and local system files.

### Packaging

```bash
./scripts/package-source.sh
```

Output:

```text
target/cppbridgej-source.zip
```

## Post-1.0 roadmap

- strings;
- structs and custom layouts;
- callbacks;
- native exception transport;
- Gradle plugin;
- WASM backend;
- optional explicit library unloading;
- additional CPU architecture validation.
