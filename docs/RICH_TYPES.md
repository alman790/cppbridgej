# Rich types

CppBridgeJ 1.1 keeps the C ABI at the boundary. Native code can use classes, templates, STL and virtual dispatch internally; export a small function for each operation Java needs. No C++ object layout, allocator, exception, or name-mangling convention is assumed.

## Struct values

```java
@CppStruct
public record Point(double x, double y) {}

@CppStruct
public record Packet(byte tag, Point origin,
                     @CppFixedArray(3) short[] samples, boolean enabled) {}

@CppModule(libraryName = "geometry")
public interface Geometry {
    Point move(Point point, double amount);
    Packet transform(Packet packet);
    void move_all(Point[] points);
    void move_pointer(NativeStruct<Point> point);
}
```

```cpp
#include <cppbridge.hpp>
struct Point { double x; double y; };
struct Packet { std::int8_t tag; Point origin; std::int16_t samples[3]; bool enabled; };

CPPBRIDGE_EXPORT Point move(Point point, double amount) {
    return {point.x + amount, point.y - amount};
}
CPPBRIDGE_EXPORT Packet transform(Packet packet) {
    packet.origin.x += 2;
    return packet;
}
CPPBRIDGE_EXPORT void move_pointer(Point* point) {
    point->x += 10;
}
CPPBRIDGE_EXPORT void move_all(Point* points, std::int32_t length) {
    for (std::int32_t i = 0; i < length; ++i) move_pointer(points + i);
}
```

`cppbridge.hpp` is installed in a build include directory by the Maven plugin. It provides `CPPBRIDGE_EXPORT` and the exception guard described below.

Records pass and return by value. Component order is native field order. The schema inserts natural padding and supports nested records, fixed arrays of records, primitive fields, enums and pointer fields. Verify native `sizeof`, `alignof` and `offsetof` against `StructType.of(Packet.class).layout()`, `byteSize()` and `offsetOf("origin")` when integrating another library. Empty structs, recursive inline fields and arrays without a positive fixed count fail during binding.

Only naturally aligned C-compatible layouts are automatic. An existing packed struct, bitfield, union, or class with a vtable requires an accessor wrapper or a suitable explicit descriptor.

## Owned storage and arrays

```java
try (NativeStruct<Point> point = NativeStruct.copyOf(Point.class, new Point(1, 2))) {
    geometry.move_pointer(point);
    Point changed = point.get();
}
```

`NativeStructArray<Point>` stores contiguous native elements with indexed `get` and `set`. A method parameter expands to `Point*` followed by `int32_t length`. Declare the concrete generic type in the interface; raw and wildcard struct parameters are rejected. Buffers are thread-confined and reject use after close.

Heap `Point[]` and enum arrays follow the same pointer/count convention and `@CppArray` directions as primitive arrays. `OUT` record arrays may start with null elements; successful calls replace them with decoded records. Passing the same array twice preserves native pointer identity. Native code must honor the length and direction, including a zero length.

Inline record arrays are copied into native storage and copied back as part of a returned record. Records passed by value do not mutate the original Java value.

## Scalars and enums

All Java primitive widths are available, including `boolean` → C++ `bool`, `short` → `int16_t`, and `char` → `char16_t`/`uint16_t`. Java `char` is a UTF-16 code unit, not C++ `char` or a portable `wchar_t`. Integral unsigned types use the same bits as their signed Java carriers; use Java unsigned conversion helpers when interpreting those bits. Java `long` always maps to 64 bits.

```java
enum Color implements CppEnum {
    RED(7), BLUE(42);
    private final int value;
    Color(int value) { this.value = value; }
    public int nativeValue() { return value; }
}
```

Match this with `enum class Color : std::int32_t { red = 7, blue = 42 };`. Values must be unique and stable. Unknown returned values throw; enum ordinals never define the ABI.

## Strings and pointers

```java
int utf8_length(String value);
@CppString(maxBytes = 1024) String title();
MemorySegment create_object();
void destroy_object(MemorySegment pointer);
```

A String argument is a temporary NUL-terminated UTF-8 `const char*`. Native code must copy it if it needs to keep the content. Java null, embedded NUL and unpaired surrogates are rejected. `@CppString` on a parameter optionally limits encoded bytes including the terminator.

A String result is a borrowed native pointer copied before the call ends. `maxBytes` includes the terminator and bounds the search; it does not prove that the allocation is readable. Null pointers return Java null; missing terminators and invalid UTF-8 throw. Free returned allocations with the matching native allocator yourself. For embedded NUL, binary strings, or explicit byte lengths, use a byte buffer.

`MemorySegment` maps to one pointer, without an implicit length. Use `MemorySegment.NULL` for null. Heap segments and closed pointers are rejected. A returned pointer has zero length until the caller supplies a known bound with FFM. Pointer fields inside records are also borrowed; copying a record does not copy their pointees. Never return a raw pointer into a temporary call argument.

## Callbacks

```java
@CppCallback
public interface Transform { double apply(double value); }

// C++: double apply(double value, double (*operation)(double));
double apply(double value, Transform operation);
```

Pass a lambda normally. Its native stub lives through that downcall, including native worker threads which join before the call returns. Parameters and results may be primitive scalars, explicit enums, pointers or record values. String and array callback parameters require pointer-based declarations with explicit bounds.

Java callback failures cannot unwind the C++ stack. The bridge records the first failure, returns a zero native value, and throws `CppBridgeException` with the original cause after returning to Java. The native function may continue after that zero result; it must define how it handles it. Existing native side effects are not rolled back.

For a callback retained by native code:

```java
try (NativeCallback<Transform> callback = NativeCallback.create(Transform.class, x -> x * 2)) {
    api.register_callback(callback.segment());
    try {
        api.run_work();
    } finally {
        api.unregister_and_join();
    }
    callback.checkFailure();
}
```

`register_callback`, `run_work` and `unregister_and_join` are operations your native API must supply. Unregister and join **before** closing the callback, including error paths. Calling a freed function pointer can crash the process. Coordinate concurrent consumers of `checkFailure`, which clears the first pending failure.

## C++ exceptions and object ownership

```cpp
CPPBRIDGE_EXPORT const char* bridge_error() noexcept {
    return cppbridge::last_error();
}
CPPBRIDGE_EXPORT std::int32_t perform() noexcept {
    return cppbridge::guard([] {
        // C++ operations may throw here; the guard catches them.
    });
}
```

```java
@CppStatus(error = "bridge_error")
void perform();
```

The native return is int32_t: zero succeeds, nonzero reads the thread-local error and throws. Ordinary C++ exceptions must never cross an FFM frame. The guard does not catch access violations, invalid pointers or memory corruption. Output heap arrays are copied back only after success; owned native buffers may already have changed.

`NativeHandle.own(pointer, api::destroy_object)` owns an opaque C++ object. Use `handle.use(pointer -> api.operation(pointer))` and try-with-resources. The handle is confined to its creating thread, prevents closing during an operation, and attempts destruction once even if the destructor wrapper throws. Do not leak the pointer out of `use`.

See [RichTypesDemo.java](../cppbridge-example/src/main/java/dev/cppbridge/example/RichTypesDemo.java) and [rich_types.cpp](../cppbridge-example/src/main/cpp/rich_types.cpp) for a complete facade over a C++ class with `std::string`, `std::vector`, `std::function`, and a templated method. It runs as part of the packaged example on every supported platform.

## Unions, custom layouts and C varargs

`CppBridge.downcall(path, symbol, descriptor, options...)` returns a standard FFM `MethodHandle`. Supply a `MemoryLayout.unionLayout(...)` inside the descriptor for a C union and a caller-owned `SegmentAllocator` as the first invocation argument when the result is a group layout.

For a fixed invocation of `double sum(int32_t count, ...)` with two doubles:

```java
MethodHandle sum = CppBridge.downcall(path, "sum",
    FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT,
                          ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE),
    Linker.Option.firstVariadicArg(1));
double result = (double) sum.invokeExact(2, 3.0, 4.5);
```

Use C's default argument promotions and an exact descriptor for each argument list. This API leaves allocation, conversion and lifetime to the caller. It does not make arbitrary C++ member functions callable without C wrappers. See the [JDK linker documentation](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/Linker.html) for platform layout requirements.
