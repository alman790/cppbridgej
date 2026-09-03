# Architecture

## Modules

```text
cppbridge-core
cppbridge-maven-plugin
cppbridge-example
cppbridge-benchmark
```

## Runtime path

```text
Java interface
  -> dynamic proxy
  -> NativeInvocationHandler
  -> MethodHandle from FFM Linker
  -> exported C function
```

## Build path

```text
src/main/cpp/*.cpp
  -> cppbridge-maven-plugin
  -> platform compiler
  -> target/native/libname.dylib|so|dll
  -> exported-symbol inspection
  -> target/cppbridge reports
```

## Type mapping

Scalars are mapped directly to FFM value layouts.

Primitive heap arrays are copied into temporary native memory and passed as:

```text
pointer, int32_t length
```

Managed native arrays expose an existing `MemorySegment` and length. They avoid heap-to-native copy on repeated calls.

## Diagnostics

There are two diagnostic layers:

- build-time symbol validation in the Maven plugin;
- runtime binding inspection through `CppBridge.inspect(...)`.

Build-time validation checks exported symbols in the compiled library. Runtime inspection checks the Java interface against the resolved native library and the supported type mapper.

## Native backend

The current backend uses Java FFM and native shared libraries.

A later WASM backend can reuse the high-level annotations, but the runtime, memory model, and build path will be separate.
