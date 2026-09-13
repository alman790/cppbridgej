# ABI Contract

CppBridgeJ provides Java-side validation and a deterministic Java-to-FFM mapping. It does not and cannot prove that a compiled C or C++ function has the same binary signature as the Java declaration. Exported-symbol validation proves that a name exists; ABI correctness remains part of the native boundary contract owned by the library author.

Use C-compatible exported functions. In C++ sources, export functions with `extern "C"` and avoid throwing exceptions across the boundary.

## Scalar Mapping

| Java type | FFM layout | Preferred C/C++ boundary type |
| --- | --- | --- |
| `byte` | `JAVA_BYTE` | `std::int8_t` or `std::uint8_t` |
| `short` | `JAVA_SHORT` | `std::int16_t` / `std::uint16_t` |
| `char` | `JAVA_SHORT` (bit-preserving conversion) | `char16_t` / `std::uint16_t` |
| `boolean` | `JAVA_BOOLEAN` | `bool` |
| `int` | `JAVA_INT` | `std::int32_t` |
| `long` | `JAVA_LONG` | `std::int64_t` |
| `float` | `JAVA_FLOAT` | `float` |
| `double` | `JAVA_DOUBLE` | `double` |
| `void` | void descriptor | `void` |

Java `long` is always 64-bit. Do not map it to C or C++ `long` in portable public examples: `long` is commonly 64-bit on LP64 Unix platforms, but it is 32-bit with MSVC on Windows. Use `std::int64_t` for Java `long` values and arrays.

Java `byte` is signed, but native byte buffers are often treated as unsigned image or DSP data. `std::int8_t*` and `std::uint8_t*` describe the same 8-bit storage width for the supported ABI boundary; choose the one that matches the native algorithm's semantics.

## Array Mapping

Primitive heap arrays and managed native arrays are passed as pointer plus signed 32-bit element count:

| Java type | Preferred C/C++ parameters |
| --- | --- |
| `byte[]`, `NativeByteArray` | `std::int8_t* values, std::int32_t length` or `std::uint8_t* values, std::int32_t length` |
| `short[]`, `NativeShortArray` | `std::int16_t* values, std::int32_t length` |
| `char[]`, `NativeCharArray` | `char16_t* values, std::int32_t length` |
| `boolean[]`, `NativeBooleanArray` | `bool* values, std::int32_t length` |
| `int[]`, `NativeIntArray` | `std::int32_t* values, std::int32_t length` |
| `long[]`, `NativeLongArray` | `std::int64_t* values, std::int32_t length` |
| `float[]`, `NativeFloatArray` | `float* values, std::int32_t length` |
| `double[]`, `NativeDoubleArray` | `double* values, std::int32_t length` |

Repeated references to the same heap array within a call preserve pointer identity. Copy directions are combined across those parameters. Native code must not declare aliased parameters with incompatible `restrict` assumptions.

`@CppArray` is valid only on supported heap-array parameters. `Void` is accepted as a return type, but never as a parameter type. Boxed scalar parameters must be non-null.

The Java side owns the `length` value. Native functions must respect it and must not read or write beyond the provided buffer.

## Validation Guarantees

CppBridgeJ validates:

- API type shape and annotations;
- scalar, enum, array, callback and record declarations;
- record field sizes, alignment, padding and fixed array counts;
- deterministic FFM function descriptors;
- eager native symbol lookup during `CppBridge.load(...)`;
- build-time exported symbol names when `expectedSymbols` is configured.

CppBridgeJ does not validate:

- native parameter or return types inside the compiled binary;
- actual sizes, field offsets, ownership, or string allocation bounds in a compiled native binary;
- calling a function with a mismatched native signature that happens to export the expected name.

For release-quality native bindings, keep exported functions small, C-compatible, fixed-width, and covered by integration tests that actually load and invoke the compiled library on every supported platform.

## Composite values and lifetime

`@CppStruct` record components appear in declaration order. Natural field alignment determines internal and trailing padding. Nested records and `@CppFixedArray(N)` components are inline. Validate `sizeof`, `alignof`, and `offsetof` against the real compiler; tests cover mixed nested layouts on every supported OS. Empty and recursive inline structs are rejected.

Record arguments and results are passed by value. Result data and inline arrays are copied before the call arena closes. Pointer fields remain borrowed. `NativeStruct<T>` passes one pointer; heap record/enum arrays and `NativeStructArray<T>` pass pointer plus int32_t count. Generic owned struct parameters require a concrete record type.

`String` arguments live through the call. String results require a positive `@CppString(maxBytes=...)`; a null native pointer returns Java null, invalid UTF-8 or a missing terminator fails. The native function must return a valid readable pointer through its terminator and within the declared bound. An optional parameter annotation also limits the encoded input size including NUL. Java null and embedded NUL inputs are rejected.

Pointer results are borrowed, zero-length FFM segments. The caller owns any reinterpretation and deallocation. Never return a raw pointer into a temporary string, record, array, or callback created for a downcall. Use a copied String/record result or caller-owned storage instead.

Callbacks expose primitives, enums, pointers and records through the same layouts. They must not throw through the native stack: CppBridgeJ catches Java failures, returns zero, then reports the failure. Pointer arguments borrowed from native code are only valid under that code's lifetime contract. A retained callback's arena must outlive every native invocation.

`@CppStatus(error="symbol")` is valid on void Java methods. The native function returns int32_t; zero succeeds and nonzero invokes the named `const char* error(void)` function and throws `CppBridgeException`. The bundled `cppbridge::guard` catches C++ exceptions and stores a thread-local message. Failure does not copy temporary output arrays back; direct buffers and native side effects may already have changed.

Explicit `CppBridge.downcall` descriptors use FFM calling conventions directly, including a caller-supplied `SegmentAllocator` for group returns and promoted arguments for C variadic calls. See the [JDK linker contract](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/Linker.html).
