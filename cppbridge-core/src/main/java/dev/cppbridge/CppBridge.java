package dev.cppbridge;

import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.diagnostics.BindingReport;
import dev.cppbridge.runtime.NativeBindingInspector;
import dev.cppbridge.runtime.NativeInvocationHandler;
import dev.cppbridge.runtime.NativeLibraryResolver;

import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Entry point for loading Java interfaces backed by native C++ functions.
 *
 * <p>An API type must be a Java interface annotated with {@link CppModule}. Each abstract method is
 * mapped to a native exported symbol. Calls are dispatched through a dynamic proxy and the Java
 * Foreign Function &amp; Memory API.
 *
 * <pre>{@code
 * @CppModule(libraryName = "fastmath")
 * interface FastMath {
 *     @CppFunction("sum_int")
 *     int sum(int a, int b);
 * }
 *
 * FastMath math = CppBridge.load(FastMath.class);
 * int result = math.sum(10, 20);
 * }</pre>
 */
public final class CppBridge {
    private CppBridge() {}

    /**
     * Loads a native implementation for the supplied API type.
     *
     * <p>The native library path is resolved from {@link CppModule#libraryPath()}, or from {@link
     * CppModule#libraryName()} and {@link CppModule#outputDirectory()} when no explicit path is
     * set.
     *
     * @param apiType annotated interface to load
     * @param <T> API interface type
     * @return proxy implementing {@code apiType}
     * @throws CppBridgeException if the interface is invalid or the native library cannot be found
     */
    public static <T> T load(Class<T> apiType) {
        CppModule module = validateApiType(apiType);
        String libraryPath = NativeLibraryResolver.resolve(apiType, module);
        return loadNative(apiType, libraryPath);
    }

    /**
     * Loads a native implementation from an explicit shared-library path.
     *
     * @param apiType annotated interface to load
     * @param libraryPathOverride path to the native shared library
     * @param <T> API interface type
     * @return proxy implementing {@code apiType}
     * @throws CppBridgeException if the interface is invalid or the native library cannot be opened
     */
    public static <T> T load(Class<T> apiType, String libraryPathOverride) {
        validateApiType(apiType);
        return loadNative(apiType, libraryPathOverride);
    }

    /**
     * Inspects the default native library for an API type and returns a binding report without
     * invoking native functions.
     *
     * @param apiType annotated API interface
     * @return report containing Java method signatures, native symbols, and validation status
     */
    public static BindingReport inspect(Class<?> apiType) {
        CppModule module = validateApiType(apiType);
        String libraryPath = NativeLibraryResolver.locate(apiType, module).toString();
        return NativeBindingInspector.inspect(apiType, module, libraryPath);
    }

    /**
     * Inspects an API type against an explicitly selected native library.
     *
     * @param apiType annotated API interface
     * @param libraryPathOverride path to the native shared library
     * @return report containing Java method signatures, native symbols, and validation status
     */
    public static BindingReport inspect(Class<?> apiType, String libraryPathOverride) {
        CppModule module = validateApiType(apiType);
        return NativeBindingInspector.inspect(apiType, module, libraryPathOverride);
    }

    /**
     * Binds an explicit FFM descriptor for unions, custom layouts and C variadic functions. The
     * caller supplies the exact ABI and any allocator required for a group return. The library
     * remains loaded for the JVM lifetime, as with load().
     */
    public static java.lang.invoke.MethodHandle downcall(
            String libraryPath,
            String symbol,
            java.lang.foreign.FunctionDescriptor descriptor,
            java.lang.foreign.Linker.Option... options) {
        Objects.requireNonNull(libraryPath, "libraryPath");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(descriptor, "descriptor");
        if (libraryPath.isBlank() || symbol.isBlank())
            throw new CppBridgeException("Library path and symbol must not be blank");
        try {
            var lookup =
                    java.lang.foreign.SymbolLookup.libraryLookup(
                            java.nio.file.Path.of(libraryPath).toAbsolutePath(),
                            java.lang.foreign.Arena.global());
            var address =
                    lookup.find(symbol)
                            .orElseThrow(
                                    () ->
                                            new CppBridgeException(
                                                    "Native symbol not found: " + symbol));
            return java.lang.foreign.Linker.nativeLinker()
                    .downcallHandle(address, descriptor, options);
        } catch (IllegalArgumentException | UnsatisfiedLinkError | IllegalCallerException error) {
            throw new CppBridgeException(
                    "Cannot bind " + symbol + ": " + error.getMessage(), error);
        }
    }

    private static <T> CppModule validateApiType(Class<T> apiType) {
        Objects.requireNonNull(apiType, "apiType");
        if (!apiType.isInterface()) {
            throw new CppBridgeException(
                    "CppBridge can load only interfaces: " + apiType.getName());
        }

        if (apiType.isSealed()) {
            throw new CppBridgeException(
                    "CppBridge requires a non-sealed interface: " + apiType.getName());
        }

        CppModule module = apiType.getAnnotation(CppModule.class);
        if (module == null) {
            throw new CppBridgeException("Missing @CppModule on interface: " + apiType.getName());
        }

        if (module.mode() == BuildMode.WASM) {
            throw new CppBridgeException(
                    "WASM backend is planned for a later version. Current version supports NATIVE"
                        + " mode.");
        }

        return module;
    }

    private static <T> T loadNative(Class<T> apiType, String libraryPath) {
        Object proxy =
                Proxy.newProxyInstance(
                        apiType.getClassLoader(),
                        new Class<?>[] {apiType},
                        new NativeInvocationHandler(apiType, libraryPath));

        return apiType.cast(proxy);
    }
}
