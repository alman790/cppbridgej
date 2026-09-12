package dev.cppbridge;

import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.memory.NativeDoubleArray;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class NativeInvocationIntegrationTest {
    @Test
    void invokesNativeScalarsHeapArraysAndManagedArrays() throws Exception {
        requireNativeCompiler();

        Path library = compileFixture();
        FixtureApi api = CppBridge.load(FixtureApi.class, library.toString());

        assertEquals(7, api.sum(3, 4));
        assertEquals(6.0, api.average(new double[] {3.0, 6.0, 9.0}));

        byte[] bytes = {1, 2, 3};
        api.fillBytes(bytes);
        assertArrayEquals(new byte[] {9, 9, 9}, bytes);

        int[] ints = {1, 2, 3};
        api.addToEach(ints, 4);
        assertArrayEquals(new int[] {5, 6, 7}, ints);

        try (NativeDoubleArray values = NativeDoubleArray.copyOf(new double[] {2.0, 4.0})) {
            api.multiplyNative(values, 3.0);
            assertArrayEquals(new double[] {6.0, 12.0}, values.toArray());
        }

        assertTrue(api.toString().contains(FixtureApi.class.getName()));
        assertEquals(System.identityHashCode(api), api.hashCode());
        assertEquals(api, api);
        assertNotEquals(api, new Object());
    }

    @Test
    void wrapsNativeBindingFailuresWithActionableMessages() throws Exception {
        requireNativeCompiler();

        Path library = compileFixture();

        CppBridgeException missingSymbol = assertThrows(
                CppBridgeException.class,
                () -> CppBridge.load(MissingSymbolApi.class, library.toString())
        );
        assertTrue(missingSymbol.getMessage().contains("Native symbol not found"));

        CppBridgeException nullArray = assertThrows(
                CppBridgeException.class,
                () -> CppBridge.load(FixtureApi.class, library.toString()).average(null)
        );
        assertTrue(nullArray.getMessage().contains("Array argument cannot be null"));
    }

    @Test
    void sharedProxyCanInvokeNativeScalarsConcurrently() throws Exception {
        requireNativeCompiler();

        Path library = compileFixture();
        FixtureApi api = CppBridge.load(FixtureApi.class, library.toString());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            int workers = 16;
            int iterations = 500;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int worker = 0; worker < workers; worker++) {
                    int workerId = worker;
                    futures.add(executor.submit(() -> {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            int left = workerId + i;
                            int right = i * 2;
                            assertEquals(left + right, api.sum(left, right));
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Test
    void defaultMethodsUseJavaImplementation() throws Exception {
        requireNativeCompiler();

        DefaultMethodApi api = CppBridge.load(DefaultMethodApi.class, compileFixture().toString());

        assertEquals(42, api.javaOnly());
        assertEquals(11, api.addOne(10));
        assertEquals(7, DefaultMethodApi.staticHelper());
        assertTrue(api.toString().contains(DefaultMethodApi.class.getName()));
        assertEquals(System.identityHashCode(api), api.hashCode());
        assertEquals(api, api);
        assertNotEquals(api, new Object());
    }

    @Test
    void repeatedHeapArrayArgumentsShareNativeMemory() throws Exception {
        requireNativeCompiler();
        AliasApi api = CppBridge.load(AliasApi.class, compileFixture().toString());
        int[] values = {3, 4};

        assertEquals(1, api.transform(values, values));
        assertArrayEquals(new int[] {7, 4}, values);

        values[0] = 3;
        assertEquals(1, api.outputFirst(values, values));
        assertArrayEquals(new int[] {7, 4}, values);

        values[0] = 3;
        assertEquals(1, api.inputOnly(values, values));
        assertArrayEquals(new int[] {3, 4}, values);

        int[] distinct = {3, 4};
        assertEquals(0, api.transform(values, distinct));
        assertArrayEquals(new int[] {3, 4}, values);
        assertArrayEquals(new int[] {7, 0}, distinct);
    }

    @Test
    void redeclaredObjectMethodsDoNotRequireNativeSymbols() throws Exception {
        requireNativeCompiler();
        ObjectMethodsApi api = CppBridge.load(ObjectMethodsApi.class, compileFixture().toString());
        assertEquals(5, api.sum(2, 3));
        assertEquals(api, api);
        assertNotEquals(api, new Object());
        assertEquals(System.identityHashCode(api), api.hashCode());
        assertTrue(api.toString().contains(ObjectMethodsApi.class.getName()));
        assertEquals(1, CppBridge.inspect(ObjectMethodsApi.class, compileFixture().toString()).entries().size());
    }

    @Test
    void packagePrivateInterfacesCanUseDefaultMethods() throws Exception {
        requireNativeCompiler();
        PackageDefaultApi api = CppBridge.load(PackageDefaultApi.class, compileFixture().toString());
        assertEquals(12, api.sumTwice(2, 4));
    }

    @Test
    void boxedScalarsRejectNullBeforeCallingNativeCode() throws Exception {
        requireNativeCompiler();
        BoxedApi api = CppBridge.load(BoxedApi.class, compileFixture().toString());
        assertEquals(7, api.sum(3, 4));
        CppBridgeException error = assertThrows(CppBridgeException.class, () -> api.sum(null, 4));
        assertTrue(error.getMessage().contains("Scalar argument cannot be null"));
    }

    private static void requireNativeCompiler() {
        boolean available = NativeTestPlatform.isCompilerAvailable();
        if (Boolean.getBoolean("cppbridge.requireNativeCompiler") && !available) {
            throw new AssertionError(NativeTestPlatform.compiler() + " is required but is not available");
        }
        assumeTrue(available, NativeTestPlatform.compiler() + " is not available");
    }

    private Path compileFixture() throws Exception {
        Path tempDir = Files.createTempDirectory(nativeFixtureRoot(), "fixture-");
        Path source = tempDir.resolve("fixture.cpp");
        Path library = tempDir.resolve(NativeTestPlatform.libraryFileName("fixture"));
        Files.writeString(source, """
                #include <cstdint>
                #ifdef _WIN32
                #define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
                #else
                #define CPPBRIDGE_EXPORT extern "C"
                #endif
                CPPBRIDGE_EXPORT std::int32_t sum_int(std::int32_t a, std::int32_t b) { return a + b; }
                CPPBRIDGE_EXPORT std::int32_t explode_error() { return 1; }
                CPPBRIDGE_EXPORT double average_double(double* values, std::int32_t length) {
                    double total = 0.0;
                    for (std::int32_t i = 0; i < length; i++) total += values[i];
                    return length == 0 ? 0.0 : total / length;
                }
                CPPBRIDGE_EXPORT void fill_bytes(std::int8_t* values, std::int32_t length) {
                    for (std::int32_t i = 0; i < length; i++) values[i] = 9;
                }
                CPPBRIDGE_EXPORT void add_ints(std::int32_t* values, std::int32_t length, std::int32_t delta) {
                    for (std::int32_t i = 0; i < length; i++) values[i] += delta;
                }
                CPPBRIDGE_EXPORT void multiply_doubles(double* values, std::int32_t length, double factor) {
                    for (std::int32_t i = 0; i < length; i++) values[i] *= factor;
                }
                CPPBRIDGE_EXPORT std::int32_t alias_transform(std::int32_t* input, std::int32_t input_length,
                        std::int32_t* output, std::int32_t output_length) {
                    if (input_length > 0 && output_length > 0) output[0] = input[0] + 4;
                    return input == output ? 1 : 0;
                }
                """, StandardCharsets.UTF_8);

        List<String> command = new ArrayList<>();
        command.add(NativeTestPlatform.compiler());
        if (NativeTestPlatform.isWindows()) {
            command.add("/nologo");
            command.add("/O2");
            command.add("/std:c++20");
            command.add("/LD");
        } else {
            command.add("-O2");
            command.add("-std=c++20");
            command.add("-shared");
            command.add("-fPIC");
        }
        command.add(source.toAbsolutePath().toString());
        if (NativeTestPlatform.isWindows()) {
            command.add("/Fe:" + library.toAbsolutePath());
        } else {
            command.add("-o");
            command.add(library.toAbsolutePath().toString());
        }

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Native fixture compilation failed: " + output);
        }
        return library;
    }

    private static Path nativeFixtureRoot() throws IOException {
        Path root = Path.of("target", "native-test-fixtures").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    @CppModule(libraryName = "fixture")
    interface FixtureApi {
        @CppFunction("sum_int")
        int sum(int a, int b);

        @CppFunction("average_double")
        double average(@CppArray(ArrayDirection.IN) double[] values);

        @CppFunction("fill_bytes")
        void fillBytes(@CppArray(ArrayDirection.OUT) byte[] values);

        @CppFunction("add_ints")
        void addToEach(@CppArray(ArrayDirection.IN_OUT) int[] values, int delta);

        @CppFunction("multiply_doubles")
        void multiplyNative(NativeDoubleArray values, double factor);
    }

    @CppModule(libraryName = "fixture")
    interface MissingSymbolApi {
        @CppFunction("missing_symbol")
        int missing();
    }

    @CppModule(libraryName = "fixture")
    interface AliasApi {
        @CppFunction("alias_transform")
        int transform(@CppArray(ArrayDirection.IN) int[] input, @CppArray(ArrayDirection.OUT) int[] output);

        @CppFunction("alias_transform")
        int outputFirst(@CppArray(ArrayDirection.OUT) int[] output, @CppArray(ArrayDirection.IN) int[] input);

        @CppFunction("alias_transform")
        int inputOnly(@CppArray(ArrayDirection.IN) int[] input, @CppArray(ArrayDirection.IN) int[] output);
    }

    @CppModule(libraryName = "fixture")
    interface ObjectMethodsApi {
        @CppFunction("sum_int")
        int sum(int a, int b);

        boolean equals(Object other);
        int hashCode();
        String toString();
    }

    @CppModule
    interface PackageDefaultApi {
        @CppFunction("sum_int")
        int sum(int a, int b);

        default int sumTwice(int a, int b) {
            return sum(a, b) * 2;
        }
    }

    @CppModule
    interface BoxedApi {
        @CppFunction("sum_int")
        Integer sum(Integer a, Integer b);
    }

    @CppModule(libraryName = "fixture")
    public interface DefaultMethodApi {
        @CppFunction("sum_int")
        int sum(int a, int b);

        default int javaOnly() {
            return 42;
        }

        default int addOne(int value) {
            return sum(value, 1);
        }

        static int staticHelper() {
            return 7;
        }
    }

    private static final class NativeTestPlatform {
        private NativeTestPlatform() {
        }

        static boolean isWindows() {
            return System.getProperty("os.name").toLowerCase().contains("win");
        }

        static String compiler() {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                return "clang++";
            }
            if (os.contains("win")) {
                return "cl";
            }
            return "g++";
        }

        static boolean isCompilerAvailable() {
            try {
                List<String> command = isWindows()
                        ? List.of("where.exe", compiler())
                        : List.of("which", compiler());
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                return process.waitFor() == 0;
            } catch (IOException exception) {
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        static String libraryFileName(String name) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                return "lib" + name + ".dylib";
            }
            if (os.contains("win")) {
                return name + ".dll";
            }
            return "lib" + name + ".so";
        }
    }
}
