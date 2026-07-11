# CI and Release

## CI

The project includes GitHub Actions configuration for Ubuntu, macOS, and Windows with JDK 22.

CI should run:

```bash
mvn clean verify
```

The Windows job activates the MSVC developer environment, verifies `cl` and `dumpbin`, runs the core and Maven plugin tests with native compiler availability required, and runs the example against the compiled native fixture:

```powershell
mvn -B -Dcppbridge.requireNativeCompiler=true -pl cppbridge-core,cppbridge-maven-plugin clean verify
mvn -B -Dcppbridge.requireNativeCompiler=true -pl cppbridge-example -am clean verify
```

The benchmark suite is not part of the regular CI path because JMH results are noisy and increase build time.

## Local release checks

```bash
mvn clean verify
./scripts/run-example.sh
./scripts/show-build-reports.sh
```

Optional benchmark check:

```bash
./scripts/run-image-benchmarks.sh
```

## Source package

```bash
./scripts/package-source.sh
```

Expected output:

```text
target/cppbridgej-source.zip
```

The source package should not include `target/`, `.DS_Store`, or local IDE files.

See `docs/PUBLISHING.md` for the release publication sequence.
