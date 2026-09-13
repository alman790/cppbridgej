# Known Limitations

- JDK 22+ and the native FFM backend are required; WASM is not implemented.
- Automatic records represent naturally aligned, standard-layout, trivially copyable C-compatible structs. Packed structs, bitfields, inheritance, virtual tables, and compiler-specific layouts need C++ accessors or an explicit FFM descriptor where the platform linker supports it.
- C++ classes, templates, STL, smart pointers and overloads require C-compatible wrappers. CppBridgeJ does not parse arbitrary C++ headers or implement the C++ ABI.
- Strings use NUL-terminated UTF-8, not the object layout of `std::string`; embedded NUL requires an explicit byte buffer. String results are borrowed and copied immediately within the declared bound. Returned native allocations are not freed automatically.
- Enum values use an explicit int32_t representation. Other underlying widths need scalar wrappers.
- Callback interfaces support primitive scalars, enums, pointers, and struct values. Temporary callbacks must finish before the downcall returns. Retained callbacks require explicit unregistration and joining native callers before close. A Java failure gives native code a zero result and is reported after returning to Java; native side effects are not rolled back.
- `NativeCallback.checkFailure()` retrieves the first pending failure; concurrent users must coordinate who consumes it. Upcall pointer arguments are borrowed; do not retain argument memory past the callback.
- Native exceptions must be caught in C++ before they reach FFM. `cppbridge::guard` and `@CppStatus` provide that convention; they cannot catch segmentation faults or repair native memory corruption.
- Managed buffers and opaque handles are thread-confined. Raw pointers and pointer fields carry no ownership information. Native code must respect sizes, lifetimes, and synchronization.
- Native libraries stay loaded for the JVM lifetime. Small native calls may cost more than equivalent Java code.
- Release binaries cover Linux x86_64, macOS aarch64, and Windows x86_64. Other systems require native rebuilding and their own ABI verification.

See [the ABI contract](ABI_CONTRACT.md) and [rich types](RICH_TYPES.md) for exact mappings and lifetime rules.
