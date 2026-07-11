# Publishing

CppBridgeJ releases are Maven artifacts plus a source archive. Do not publish from a dirty worktree.

## Preflight

Use JDK 22 unless a release issue explicitly records a newer baseline:

```bash
java -version
mvn -version
git status --short --branch
```

Run the release checks:

```bash
mvn -B clean verify
mvn -B -Pcoverage clean verify
./scripts/run-example.sh
./scripts/show-build-reports.sh
```

Run at least one JMH smoke from the benchmark module before changing benchmark documentation:

```bash
mvn -B -pl cppbridge-benchmark -am clean package
cd cppbridge-benchmark
java --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar 'ArrayBenchmarks\.(javaAverageForLoop|cppAverageFfmNativeArray)' -wi 1 -i 1 -f 1 -r 100ms -w 100ms
```

## Versioning

Update the root version and all module parent versions together. For the next release after `1.0.0-rc2`, use `1.0.0-rc3` unless the release owner chooses to cut `1.0.0`.

Update:

- `CHANGELOG.md`
- `docs/RELEASE_CHECKLIST.md` if the process changed
- benchmark docs only when new benchmark numbers were collected

## Source Package

```bash
./scripts/package-source.sh
```

Check that the archive excludes `target/`, `.DS_Store`, and local IDE files.

## Tagging

Tag only after CI is green on the release commit:

```bash
git tag -a v1.0.0-rc3 -m "CppBridgeJ 1.0.0-rc3"
git push origin v1.0.0-rc3
```

## Maven Publication

Publish from the release commit only. Keep credentials outside the repository in Maven settings or the CI secret store.

```bash
mvn -B -DskipTests deploy
```

After publication, verify that the expected artifacts are visible in the target repository and that a fresh consumer project resolves both `cppbridge-core` and `cppbridge-maven-plugin`.
