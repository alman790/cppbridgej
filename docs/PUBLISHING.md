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

The Maven Invoker integration tests include a consumer-style smoke project. It resolves `cppbridge-core` and `cppbridge-maven-plugin` from the isolated Invoker local repository, compiles a C++ fixture, validates exported symbols, loads the generated shared library, and invokes scalar and array functions.

Run at least one JMH smoke from the benchmark module before changing benchmark documentation:

```bash
mvn -B -pl cppbridge-benchmark -am clean package
cd cppbridge-benchmark
java --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar 'ArrayBenchmarks\.(javaAverageForLoop|cppAverageFfmNativeArray)' -wi 1 -i 1 -f 1 -r 100ms -w 100ms
```

## Versioning

The development version is `1.0.0-rc4-SNAPSHOT`. Before publishing, update the root version and all module parent versions together to a release version, such as `1.0.0-rc4` or `1.0.0`. Do not publish the development snapshot to Maven Central. The tagging example below assumes `1.0.0-rc4`.

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
git tag -a v1.0.0-rc4 -m "CppBridgeJ 1.0.0-rc4"
git push origin v1.0.0-rc4
```

## Maven Publication

Publish from the release commit only. Keep credentials and signing material outside the repository in Maven settings or the CI secret store.

CppBridgeJ uses the Central Portal publishing flow through `org.sonatype.central:central-publishing-maven-plugin`. Configure a Maven server named `central` with the token username/password issued by the Central Portal, and configure GPG signing locally or in CI. Do not commit credentials or keys.

```bash
mvn -B -Pcentral-publish -DskipTests deploy
```

The parent POM, `cppbridge-core`, and `cppbridge-maven-plugin` are deployable. `cppbridge-example` and `cppbridge-benchmark` set both Maven deploy skip and Central Portal `skipPublishing` through the shared skip property, so they must not appear in the Central bundle.

Expected public artifacts:

- `cppbridge-core` main JAR, sources JAR, JavaDoc JAR, POM, signatures;
- `cppbridge-maven-plugin` main JAR, sources JAR, JavaDoc JAR, POM, signatures;
- parent POM and signature.

After publication, verify that the expected artifacts are visible in the target repository and that a fresh consumer project resolves both `cppbridge-core` and `cppbridge-maven-plugin`.
