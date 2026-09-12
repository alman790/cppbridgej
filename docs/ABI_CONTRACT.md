# ABI Contract

CppBridgeJ provides Java-side validation and a deterministic Java-to-FFM mapping. It does not and cannot prove that a compiled C or C++ function has the same binary signature as the Java declaration. Exported-symbol validation proves that a name exists; ABI correctness remains part of the native boundary contract owned by the library author.

Use C-compatible exported functions. In C++ sources, export functions with `extern "C"` and avoid throwing exceptions across the boundary.

## Scalar Mapping

| Java type | FFM layout | Preferred C/C++ boundary type |
| --- | --- | --- |
| `byte` | `JAVA_BYTE` | `std::int8_t` or `std::uint8_t` |
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
- supported Java scalar and array types;
- deterministic FFM function descriptors;
- eager native symbol lookup during `CppBridge.load(...)`;
- build-time exported symbol names when `expectedSymbols` is configured.

CppBridgeJ does not validate:

- native parameter or return types inside the compiled binary;
- struct layouts, strings, callbacks, or exception transport;
- calling a function with a mismatched native signature that happens to export the expected name.

For release-quality native bindings, keep exported functions small, C-compatible, fixed-width, and covered by integration tests that actually load and invoke the compiled library on every supported platform.
