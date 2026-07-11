# Testing

## Unit tests

Core unit tests cover:

- binding report generation;
- unsupported signature reporting;
- managed native array allocation and copying;
- default-method dispatch and native exception wrapping;
- concurrent calls through one shared proxy.

## Integration tests

`cppbridge-example` compiles C++ through the Maven plugin and calls the resulting shared library through the public Java API.

Test path:

```text
Maven plugin -> C++ compiler -> shared library -> CppBridge.load -> FFM call -> assertion
```

`cppbridge-maven-plugin` also runs Maven Invoker fixture projects during `verify`. The fixtures cover successful compilation, missing expected symbols, compiler failure, custom compiler arguments and output directory, absent source directories, and a consumer project that loads the generated shared library through `CppBridge.load(...)`.

Use native compiler enforcement when checking release candidates:

```bash
mvn -Dcppbridge.requireNativeCompiler=true -pl cppbridge-core,cppbridge-maven-plugin test
```

## Benchmarks

JMH benchmarks are stored in `cppbridge-benchmark`.

They compare:

- Java loops;
- C++ calls with heap-array marshalling;
- C++ calls with managed native arrays;
- separate native calls vs fused native kernels.

Benchmarks are not correctness tests.
