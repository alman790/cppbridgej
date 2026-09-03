# Binding Report

`CppBridge.inspect(...)` checks how a Java interface maps to a native library.

```java
BindingReport report = CppBridge.inspect(FastMath.class);
System.out.println(report.toText());
```

Example output:

```text
CppBridgeJ binding report
API: dev.cppbridge.example.FastMath
Mode: NATIVE
Library exists: true
Healthy: true

- double average(double[])
  -> double average_double(double*, int32_t length)
  symbol: average_double
  status: OK
```

## Status values

- `OK`: the Java method can be mapped to a native symbol.
- `MISSING_LIBRARY`: the configured library file does not exist.
- `MISSING_SYMBOL`: the library exists, but the symbol was not found.
- `UNSUPPORTED_SIGNATURE`: the Java method uses unsupported types.
- `INSPECTION_FAILED`: native inspection failed.

## Use cases

- checking configuration before runtime calls;
- debugging symbol names;
- documenting Java-to-native mappings;
- validating interface changes during development.

Build-time symbol checks are handled separately by the Maven plugin.
