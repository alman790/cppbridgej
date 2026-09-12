# User Guide

## Model

```text
Java interface
  -> annotations
  -> Maven plugin compiles C++
  -> shared library
  -> FFM MethodHandle
  -> Java proxy
```

## Module mapping

```java
@CppModule(libraryName = "fastmath")
public interface FastMath {
}
```

Platform output paths:

```text
macOS   target/native/libfastmath.dylib
Linux   target/native/libfastmath.so
Windows target/native/fastmath.dll
```

When no explicit path is configured and the file is absent, the runtime searches the API's class loader for `META-INF/cppbridge/<os>-<arch>/<library>`. The Maven plugin includes this resource in the JAR by default. Architectures use canonical names such as `x86_64` and `aarch64`.

Packaged libraries are extracted once per resource URL to a private temporary directory and scheduled for deletion at JVM shutdown. On platforms that lock loaded binaries, deletion may require later temporary-directory cleanup. Each JAR contains the platform it was built for; dependent shared libraries are not bundled automatically.

`CppBridge.load(Api.class, path)` and `@CppModule(libraryPath = "...")` select a specific file and do not fall back to another library. `CppBridge.inspect(...)` uses the same resolution order. Conflicting resources with the same native name fail with an explanation instead of choosing one by classpath order.

## Function mapping

```java
@CppFunction("sum_int")
int sum(int a, int b);
```

Native side:

```cpp
CPPBRIDGE_EXPORT std::int32_t sum_int(std::int32_t a, std::int32_t b) {
    return a + b;
}
```

Only abstract interface methods other than `equals(Object)`, `hashCode()`, and `toString()` are native bindings. Default and static interface methods remain Java methods and are ignored by the runtime binding inspector and by `CppBridge.load(...)` native-symbol resolution.

## Scalars

Supported scalar Java types:

```text
byte, int, long, float, double, void
```

Use fixed-width native types at the ABI boundary where width matters. Java `long` maps to a 64-bit FFM layout, so portable C++ code should use `std::int64_t`, not C/C++ `long`. See `ABI_CONTRACT.md` for the full mapping and validation guarantees.

## Heap arrays

Primitive arrays are mapped as pointer plus length.

Java:

```java
@CppFunction("average_double")
double average(@CppArray(ArrayDirection.IN) double[] values);
```

C++:

```cpp
CPPBRIDGE_EXPORT double average_double(double* values, std::int32_t length);
```

Array direction:

- `IN`: Java to native;
- `OUT`: native to Java;
- `IN_OUT`: both directions.

Default is `IN_OUT`. `OUT` buffers start zero-filled. If the same heap array is passed to multiple parameters, all of them receive the same native pointer. Their directions are combined: any input parameter enables copy-in, and any output parameter enables copy-back. Copies happen once per array per call.

`@CppArray` is only valid on supported primitive heap arrays. Managed arrays always pass their existing memory directly. Null arrays and null boxed scalars are rejected before the native call.

## Managed native arrays

Use managed native arrays when data is reused across multiple native calls:

```java
try (NativeDoubleArray values = NativeDoubleArray.copyOf(heapValues)) {
    math.heavyTransform(values);
    math.multiplyEachNative(values, 2.0);
    double[] result = values.toArray();
}
```

Supported wrappers:

```text
NativeByteArray
NativeIntArray
NativeLongArray
NativeFloatArray
NativeDoubleArray
```

Managed native arrays own confined FFM arenas. Use each instance from the thread that created it, including `segment()`, copy helpers, native calls that receive it, and `close()`.

## Build-time symbol validation

```xml
<expectedSymbols>
    <expectedSymbol>sum_int</expectedSymbol>
    <expectedSymbol>average_double</expectedSymbol>
</expectedSymbols>
```

If a configured symbol is missing, the Maven build fails. Reports are written to `target/cppbridge/`.

The plugin accepts only defined exported symbols. Undefined or imported entries such as `U missing_symbol` from `nm` are ignored and cannot satisfy `expectedSymbols`.

## Runtime inspection

```java
BindingReport report = CppBridge.inspect(FastMath.class);
System.out.println(report.toText());
```

Use this when checking how Java methods map to native symbols.

`CppBridge.load(...)` performs the same bindable-method selection as the inspector. A missing native symbol or unsupported signature fails at load time before the first native call.

## Threading

`CppBridge.load(...)` returns a proxy that can be shared between threads after construction. Method bindings are prepared once and stored in an immutable map and heap-array marshalling uses per-call temporary memory.

Sharing Java arrays or managed native arrays between concurrent calls is still the caller's responsibility. Native functions must be reentrant for concurrent use, and mutable buffers need external synchronization when multiple threads access the same storage.

## Usage guidelines

Suitable workloads:

- large image or byte buffers;
- numerical transforms;
- repeated operations over one native buffer;
- matrix, audio, simulation, or codec kernels.

Avoid:

- tiny native methods called inside Java loops;
- repeated heap-to-native copies for small arrays;
- exposing complex C++ types directly through the ABI.
