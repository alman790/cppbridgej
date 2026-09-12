# Quickstart

This example creates a separate Maven application that calls C++ from Java. It uses version `1.0.0`.

## Install the artifacts

Download `cppbridgej-1.0.0-maven.zip` from [the release](https://github.com/alman790/cppbridgej/releases/tag/v1.0.0). It contains the runtime, plugin, parent POM, sources, and JavaDoc in Maven repository layout. Extract it into your local Maven repository (or your configured alternative):

```bash
unzip cppbridgej-1.0.0-maven.zip -d "$HOME/.m2/repository"
```

PowerShell:

```powershell
Expand-Archive cppbridgej-1.0.0-maven.zip -DestinationPath "$env:USERPROFILE/.m2/repository"
```

Maven Central publication is pending. Alternatively, build and install from source. With JDK 22+ selected and a C++ compiler on `PATH`, run from the CppBridgeJ checkout:

```bash
./mvnw clean install
```

Use `mvnw.cmd` on Windows. MSVC builds must run in a Developer Command Prompt with `cl` and `dumpbin` available. On macOS, select a JDK with `/usr/libexec/java_home` if `java -version` reports an older version.

For the application below, use Maven 3.9+ or copy `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` into its directory.

## Create the application

Create `pom.xml` in a new directory:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>example</groupId>
    <artifactId>native-demo</artifactId>
    <version>1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.release>22</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <cppbridge.version>1.0.0</cppbridge.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>dev.cppbridge</groupId>
            <artifactId>cppbridge-core</artifactId>
            <version>${cppbridge.version}</version>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.15.0</version>
            </plugin>
            <plugin>
                <groupId>dev.cppbridge</groupId>
                <artifactId>cppbridge-maven-plugin</artifactId>
                <version>${cppbridge.version}</version>
                <configuration>
                    <libraryName>fastmath</libraryName>
                    <expectedSymbols>
                        <expectedSymbol>average_double</expectedSymbol>
                    </expectedSymbols>
                </configuration>
                <executions>
                    <execution>
                        <goals><goal>compile-cpp</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <configuration>
                    <executable>${java.home}/bin/java</executable>
                    <arguments>
                        <argument>--enable-native-access=ALL-UNNAMED</argument>
                        <argument>-classpath</argument>
                        <classpath/>
                        <argument>example.Main</argument>
                    </arguments>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

Create `src/main/cpp/fastmath.cpp`:

```cpp
#include <cstdint>

#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

CPPBRIDGE_EXPORT double average_double(const double* values, std::int32_t length) {
    double total = 0.0;
    for (std::int32_t i = 0; i < length; ++i) {
        total += values[i];
    }
    return length == 0 ? 0.0 : total / length;
}
```

Create `src/main/java/example/FastMath.java`:

```java
package example;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;

@CppModule(libraryName = "fastmath")
public interface FastMath {
    @CppFunction("average_double")
    double average(@CppArray(ArrayDirection.IN) double[] values);
}
```

Create `src/main/java/example/Main.java`:

```java
package example;

import dev.cppbridge.CppBridge;

public final class Main {
    public static void main(String[] args) {
        FastMath math = CppBridge.load(FastMath.class);
        System.out.println(math.average(new double[]{10.0, 20.0, 30.0}));
    }
}
```

## Run

From the application's directory:

```bash
mvn package exec:exec
```

The application prints `20.0`. The run configuration starts the same JDK used by Maven and enables native access in the application JVM.

`target/native` contains the compiled library. The application JAR also includes it under `META-INF/cppbridge/<os>-<arch>/`. When distributing the JAR, include `cppbridge-core` on the application's classpath as you would any other runtime dependency. The working directory no longer needs a `target/native` folder.

For a different operating system or CPU, build on that target and distribute its native binary. This plugin does not cross-compile or gather dependent shared libraries.

## Troubleshooting

| Problem | Check |
| --- | --- |
| Maven cannot resolve `dev.cppbridge` artifacts | Run `./mvnw clean install` in this checkout first and use its version in the application. |
| Native library not found | Match `libraryName` in the plugin and `@CppModule`; run `mvn package`. |
| Cannot open native library | Match the JVM's architecture and install any dependent native libraries. |
| Native symbol not found | Export with `extern "C"`; add `__declspec(dllexport)` on Windows. |
| Native-access warning or failure | Start the application JVM with `--enable-native-access=ALL-UNNAMED`. |
| Duplicate native resources | Remove duplicate dependencies or use an explicit path with `CppBridge.load(...)`. |
| Compiler times out | Fix the blocked command or increase `cppbridge.commandTimeoutSeconds` from its 300-second default. |

To see which bindings resolve, print `CppBridge.inspect(FastMath.class).toText()`. Build-time reports are written to `target/cppbridge/`.
