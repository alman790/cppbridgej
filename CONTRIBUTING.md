# Contributing

## Development requirements

- JDK 22+
- Maven 3.9+
- `clang++`, `g++`, or MSVC `cl`

## Local validation

```bash
mvn -Pcoverage clean verify
./scripts/run-example.sh
./scripts/show-build-reports.sh
```

The `coverage` profile runs JaCoCo and enforces the current project coverage
ratchet. Raise the threshold when a change increases meaningful coverage; do not
lower it to merge unrelated work.

Run benchmark groups only when working on runtime performance:

```bash
./scripts/run-array-benchmarks.sh
./scripts/run-pipeline-benchmarks.sh
./scripts/run-image-benchmarks.sh
```

## Native ABI rules

Export functions with a C ABI:

```cpp
#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif
```

Do not expose C++ classes, templates, exceptions, or STL types through the public native boundary. Use primitive scalars and primitive arrays.

## Pull requests

A change that adds or changes runtime behavior should include at least one of:

- unit test;
- integration test;
- benchmark update;
- documentation update.

Performance notes should include hardware, JVM version, compiler version, benchmark command, and JMH output.

## Testing expectations

- API validation and diagnostics should be covered with unit tests.
- Native invocation behavior should be covered with integration tests that
  compile a small fixture library.
- Maven plugin behavior should avoid shell-specific assumptions and should keep
  platform-specific behavior isolated behind `Platform`.
- Benchmarks are not correctness tests. Keep benchmark changes separate from
  runtime behavior changes when possible.

## Release checklist

Maintainers should release from a clean `main` branch:

```bash
mvn -Pcoverage clean verify
./scripts/package-source.sh
git tag vX.Y.Z
git push origin main vX.Y.Z
```

Pushing a `v*` tag builds release artifacts and creates a GitHub release. Maven
Central publication requires repository secrets and should be added only after
the project owner configures signing and deployment credentials.
