# Quickstart

## 1. Requirements

- JDK 22+
- Maven 3.9+
- C++ compiler (`clang++`, `g++`, or MSVC `cl`)

```bash
java -version
mvn -v
```

## 2. Build

```bash
mvn clean install
```

## 3. Run tests

```bash
./scripts/run-tests.sh
```

## 4. Run the example

```bash
./scripts/run-example.sh
```

Expected output includes:

```text
sum(10, 20) = 30
average = 25.0
sumLongArrayNative = 15
after brightenNative = [30, 120, 255]
```

## 5. Inspect build reports

```bash
./scripts/show-build-reports.sh
```

Reports are generated under:

```text
*/target/cppbridge/native-build-report.txt
*/target/cppbridge/exported-symbols.txt
*/target/cppbridge/missing-symbols.txt
```

## 6. Run benchmarks

```bash
./scripts/run-array-benchmarks.sh
./scripts/run-pipeline-benchmarks.sh
./scripts/run-image-benchmarks.sh
```

Direct JMH usage:

```bash
cd cppbridge-benchmark
java -jar target/benchmarks.jar ImageBenchmarks
```

## 7. Minimal C++ export

```cpp
#include <cstdint>

#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

CPPBRIDGE_EXPORT std::int32_t sum_int(std::int32_t a, std::int32_t b) {
    return a + b;
}
```

## 8. Minimal Java interface

```java
@CppModule(libraryName = "fastmath")
public interface FastMath {
    @CppFunction("sum_int")
    int sum(int a, int b);
}
```

## Troubleshooting

If Maven cannot resolve `dev.cppbridge:cppbridge-core`, run the build from the repository root:

```bash
mvn clean install
./scripts/run-example.sh
```
