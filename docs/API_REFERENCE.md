# API Reference

## `CppBridge`

```java
T CppBridge.load(Class<T> api)
T CppBridge.load(Class<T> api, String libraryPath)
BindingReport CppBridge.inspect(Class<?> api)
BindingReport CppBridge.inspect(Class<?> api, String libraryPath)
```

`load` creates a dynamic proxy for a Java interface annotated with `@CppModule`. Bindable abstract methods are resolved eagerly, so missing native symbols and unsupported signatures fail during load.

`inspect` returns runtime binding diagnostics without invoking native functions.

## `@CppModule`

```java
@CppModule(libraryName = "fastmath")
public interface FastMath {
}
```

Main attributes:

- `libraryName`: platform-neutral library name;
- `libraryPath`: optional explicit path;
- `outputDirectory`: development library directory, defaulting to `target/native`;
- `mode`: currently `NATIVE`.

## `@CppFunction`

```java
@CppFunction("average_double")
double average(double[] values);
```

Maps a Java interface method to an exported native symbol.

If the annotation value is empty, the Java method name is used as the native symbol. Default interface methods are not native bindings and execute as Java default methods.

## `@CppArray`

```java
@CppArray(ArrayDirection.IN)
```

Controls heap-array copy direction.

Values:

- `IN`
- `OUT`
- `IN_OUT`

## Supported scalar types

| Java type | Native boundary type |
| --- | --- |
| `byte` | `std::int8_t` or `std::uint8_t` |
| `int` | `std::int32_t` |
| `long` | `std::int64_t` |
| `float` | `float` |
| `double` | `double` |
| `void` | `void` |

CppBridgeJ validates Java declarations and exported symbol names. It does not inspect compiled native function signatures. The native library author must keep the exported C-compatible ABI exactly aligned with the Java interface. See `ABI_CONTRACT.md`.

## Supported heap arrays

```text
byte[]
int[]
long[]
float[]
double[]
```

Heap arrays are mapped to pointer plus a signed 32-bit `length`.

## Managed native arrays

```text
NativeByteArray
NativeIntArray
NativeLongArray
NativeFloatArray
NativeDoubleArray
```

Common methods:

```java
static NativeDoubleArray allocate(int length)
static NativeDoubleArray copyOf(double[] values)
int length()
MemorySegment segment()
double[] toArray()
void close()
```

Managed native arrays use confined arenas and are thread-confined to their creating thread.

## Diagnostics

```java
BindingReport report = CppBridge.inspect(FastMath.class);
boolean healthy = report.isHealthy();
String text = report.toText();
```

Entry status values:

```text
OK
LIBRARY_NOT_FOUND
MISSING_SYMBOL
UNSUPPORTED_SIGNATURE
INSPECTION_FAILED
```

Binding reports include only bindable abstract interface methods. Default and static methods are skipped so runtime diagnostics and proxy loading agree on the same native surface.
