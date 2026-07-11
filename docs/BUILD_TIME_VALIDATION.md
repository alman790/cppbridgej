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
