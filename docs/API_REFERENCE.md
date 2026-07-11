# API Reference

## `CppBridge`

```java
T CppBridge.load(Class<T> api)
T CppBridge.load(Class<T> api, Path libraryPath)
BindingReport CppBridge.inspect(Class<?> api)
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

```text
byte
int
long
float
double
void
```

## Supported heap arrays

```text
byte[]
int[]
long[]
float[]
double[]
```

Heap arrays are mapped to pointer plus `int length`.

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
MISSING_LIBRARY
MISSING_SYMBOL
UNSUPPORTED_SIGNATURE
INSPECTION_FAILED
```

Binding reports include only bindable abstract interface methods. Default and static methods are skipped so runtime diagnostics and proxy loading agree on the same native surface.
