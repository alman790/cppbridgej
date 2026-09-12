# Versioning

Current release version:

```text
1.0.0
```

CppBridgeJ follows semantic versioning after `1.0.0`:

- `1.0.x`: backward-compatible bug and security fixes;
- `1.x`: backward-compatible features where practical;
- `2.0`: intentional public API breaks.

Native ABI compatibility also depends on user-provided C-compatible declarations. A Java-compatible CppBridgeJ upgrade cannot make a mismatched native function signature safe.

Stable API surface:

- `CppBridge.load(...)`
- `CppBridge.inspect(...)`
- `@CppModule`
- `@CppFunction`
- `@CppArray`
- `ArrayDirection`
- managed native arrays
- Maven plugin `compile-cpp`
- build-time expected symbol validation

Potential post-1.0 additions:

- Gradle plugin;
- WASM backend;
- generated binding metadata;
- richer type layouts;
- Spring Boot integration.
