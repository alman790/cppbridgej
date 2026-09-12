# JavaDoc

Generate JavaDoc with the helper script:

```bash
./scripts/generate-javadocs.sh
```

The script verifies that the expected `index.html` files exist after generation.

Generated files:

```text
cppbridge-core/target/reports/apidocs/index.html
cppbridge-maven-plugin/target/reports/apidocs/index.html
```

Equivalent Maven commands:

```bash
mvn -pl cppbridge-core -DskipTests javadoc:javadoc
mvn -pl cppbridge-maven-plugin -DskipTests javadoc:javadoc
```

The generated JavaDoc is build output and should not be committed.
