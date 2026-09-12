# CppBridgeJ

Call C++ functions from Java through the Foreign Function & Memory API. Declare a Java interface, export C-compatible functions, and let the Maven plugin compile and package the native library.

CppBridgeJ works best for operations over whole buffers: image processing, numerical transforms, audio, and simulation steps. Small calls can cost more than the equivalent Java code.

## Build and run

You need JDK 22 or newer and a C++ compiler: `g++` on Linux, `clang++` on macOS, or MSVC `cl` and `dumpbin` in a Windows Developer Command Prompt. Maven Wrapper is included.

```bash
./mvnw clean install
./scripts/run-example.sh
```

On Windows, use `mvnw.cmd`. An existing Maven 3.9+ installation also works.

The development version is `1.0.0-rc4-SNAPSHOT`. Install it from this checkout before using it in another project; this version is not a published release.

See [Quickstart](docs/QUICKSTART.md) for a complete application with its own `pom.xml`, C++ source, and Java entry point.

## Declare the native API

`src/main/cpp/fastmath.cpp`:

```cpp
#include <cstdint>

#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

CPPBRIDGE_EXPORT double average_double(const double* values, std::int32_t length) {
    double total = 0.0;
    for (std::int32_t i = 0; i < length; ++i) {
        total += values[i];
    }
    return length == 0 ? 0.0 : total / length;
}
```

Java:

```java
import dev.cppbridge.ArrayDirection;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;

@CppModule(libraryName = "fastmath")
public interface FastMath {
    @CppFunction("average_double")
    double average(@CppArray(ArrayDirection.IN) double[] values);
}
```

```java
FastMath math = CppBridge.load(FastMath.class);
double result = math.average(new double[]{10.0, 20.0, 30.0});
```

The array becomes a native pointer followed by an `int32_t` element count. Exported function signatures must match this contract; symbol lookup cannot check C++ parameter types.

## Add it to a Maven project

Add the runtime dependency:

```xml
<dependency>
    <groupId>dev.cppbridge</groupId>
    <artifactId>cppbridge-core</artifactId>
    <version>1.0.0-rc4-SNAPSHOT</version>
</dependency>
```

Add the plugin under `build/plugins`. The library name must match `@CppModule`:

```xml
<plugin>
    <groupId>dev.cppbridge</groupId>
    <artifactId>cppbridge-maven-plugin</artifactId>
    <version>1.0.0-rc4-SNAPSHOT</version>
    <configuration>
        <libraryName>fastmath</libraryName>
        <expectedSymbols>
            <expectedSymbol>average_double</expectedSymbol>
        </expectedSymbols>
    </configuration>
    <executions>
        <execution>
            <goals><goal>compile-cpp</goal></goals>
        </execution>
    </executions>
</plugin>
```

`mvn package` compiles `.cpp`, `.cc`, and `.cxx` files recursively under `src/main/cpp`. It writes the shared library to `target/native` and includes it in the JAR under `META-INF/cppbridge/<os>-<arch>/`.

Start Java with `--enable-native-access=ALL-UNNAMED`. The [Quickstart](docs/QUICKSTART.md) includes a Maven run configuration with this flag.

## Library loading

`CppBridge.load(Api.class)` looks for:

1. `@CppModule(libraryPath = "...")`, when set. A missing explicit path is an error.
2. The library in `outputDirectory`, which defaults to `target/native`.
3. A matching platform resource in the API's class loader, extracted to a private temporary directory.

A packaged application can run from any working directory. JARs contain the platform built on that machine; build and distribute each supported OS/CPU combination separately. Native dependencies still need to be installed or otherwise made available to the OS loader.

Use `CppBridge.load(Api.class, "/absolute/path/to/library")` for an explicit runtime override. `CppBridge.inspect(...)` uses the same locations and returns binding diagnostics without invoking API methods. Multiple matching classpath resources are reported as an error.

Set `<packageNative>false</packageNative>` to distribute shared libraries separately. Native libraries remain loaded for the JVM lifetime.

## Arrays and threading

| Java parameter | Native parameters | Copy behavior |
| --- | --- | --- |
| `@CppArray(IN) double[]` | `double*, int32_t` | Copy in |
| `@CppArray(OUT) double[]` | `double*, int32_t` | Zero-initialize, then copy out |
| `double[]` or `@CppArray(IN_OUT) double[]` | `double*, int32_t` | Copy in and out |
| `NativeDoubleArray` | `double*, int32_t` | Pass existing native memory |

The same rules apply to `byte`, `int`, `long`, and `float` arrays. Passing the same heap array to several parameters preserves one native pointer; their copy directions are combined. Null arrays and null boxed scalars are rejected before invocation.

For repeated operations over a buffer, use a managed array in try-with-resources. Each managed array belongs to its creating thread. A proxy can be shared across threads if the native functions and the caller's buffers support concurrent use.

Default interface methods run in Java. Static methods and the standard `Object` methods are not native bindings. Native symbols and Java signatures are validated when the proxy is loaded.

## Development

```bash
./mvnw -Pcoverage clean verify
./scripts/generate-javadocs.sh
```

Tests include real C++ compilation, array marshalling, concurrent calls, failure cases, and a separate JVM loading a packaged consumer JAR. CI runs on Linux, macOS, and Windows.

For performance work, use the [JMH benchmarks](docs/PERFORMANCE_NOTES.md). Previous measurements are in [Benchmark results](docs/BENCHMARK_RESULTS_MACBOOK_JDK22.md); remeasure for your hardware and workload.

## Documentation

- [Quickstart](docs/QUICKSTART.md)
- [User guide](docs/USER_GUIDE.md)
- [ABI contract](docs/ABI_CONTRACT.md)
- [Build configuration and symbol validation](docs/BUILD_TIME_VALIDATION.md)
- [Binding diagnostics](docs/BINDING_REPORT.md)
- [API reference](docs/API_REFERENCE.md)
- [Limitations](docs/KNOWN_LIMITATIONS.md)
- [Publishing](docs/PUBLISHING.md)

The supported boundary consists of primitive scalars, primitive arrays, and managed native arrays. Strings, structs, callbacks, C++ classes, exception transport, and WASM are not implemented.
