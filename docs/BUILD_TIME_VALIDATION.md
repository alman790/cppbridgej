# Build-time Validation

The Maven plugin can validate exported native symbols after compilation.

```xml
<configuration>
    <libraryName>fastmath</libraryName>
    <expectedSymbols>
        <expectedSymbol>sum_int</expectedSymbol>
        <expectedSymbol>average_double</expectedSymbol>
    </expectedSymbols>
    <failOnMissingSymbols>true</failOnMissingSymbols>
</configuration>
```

## Generated files

```text
target/cppbridge/native-build-report.txt
target/cppbridge/exported-symbols.txt
target/cppbridge/exported-symbols-raw.txt
target/cppbridge/missing-symbols.txt
```

## Report contents

The native build report includes:

- platform;
- library name;
- output library path;
- source directory;
- compiler command;
- compiler exit code;
- compiled C++ source files;
- expected symbols;
- symbol inspection command;
- exported symbol count;
- missing symbol count.

## Symbol tools

Platform inspection commands:

```text
macOS:  nm -gU <library>
Linux:  nm -D --defined-only -g <library>
Windows: dumpbin /EXPORTS <library>
```

Only defined external symbols are considered exports. Undefined or imported symbols are ignored, so output like `U missing_symbol` from `nm` does not satisfy `expectedSymbols`.

If the inspection command cannot run or exits with an error while `expectedSymbols` is configured, the build fails with a symbol-inspection error. This is distinct from a successful inspection that reports missing symbols.

If symbol validation fails, check that exported functions use `extern "C"` and that names in `expectedSymbols` match the C ABI names exactly. On Windows, exported functions must use `__declspec(dllexport)` or an equivalent export mechanism.

## Compilation and packaging

The plugin runs during `generate-resources`. It compiles sources recursively, validates configured symbols, then copies the successful binary into the Java output directory under `META-INF/cppbridge/<os>-<arch>/`. Standard JAR packaging includes that resource.

Additional configuration:

```xml
<configuration>
    <libraryName>fastmath</libraryName>
    <includeDirectories>
        <includeDirectory>src/main/include</includeDirectory>
        <includeDirectory>vendor/include</includeDirectory>
    </includeDirectories>
    <extraCompilerArgs>
        <arg>-Wall</arg>
    </extraCompilerArgs>
    <extraLinkerArgs>
        <arg>-lm</arg>
    </extraLinkerArgs>
    <commandTimeoutSeconds>300</commandTimeoutSeconds>
    <packageNative>true</packageNative>
</configuration>
```

The `-Wall` and `-lm` flags above are Unix examples. Use the equivalent compiler/linker arguments for MSVC. Include paths are individual arguments, so directories with spaces work without embedded shell quotes. Relative paths are resolved from the owning Maven project, including when Maven runs from the reactor root. Linker arguments follow the source files; MSVC receives them after `/link`.

| Property | Default | Purpose |
| --- | --- | --- |
| `cppbridge.compiler` | Platform compiler | Select an executable |
| `cppbridge.cppStandard` | `c++20` | Select `c++17`, `c++20`, or `c++23` |
| `cppbridge.optimizationLevel` | `O3` | Select `O0` through `O3` |
| `cppbridge.commandTimeoutSeconds` | `300` | Bound each compiler or symbol-tool invocation |
| `cppbridge.packageNative` | `true` | Include the library in the JAR |
| `cppbridge.skip` | `false` | Skip native compilation |

For example: `./mvnw -Dcppbridge.optimizationLevel=O0 package`. Use a clean build when changing packaging settings or removing native sources.

Timed-out or interrupted commands are terminated with their child processes. The interrupt flag is preserved. Compiler and symbol-tool output is captured without blocking on a full pipe.

JAR packaging targets the OS and CPU of the build JVM. The compiler must target the same platform. Cross-compilation and automatic packaging of dependent shared libraries are not provided.
