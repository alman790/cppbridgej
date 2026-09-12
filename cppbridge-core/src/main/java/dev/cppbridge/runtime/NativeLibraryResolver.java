package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppModule;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves native library paths from {@link CppModule} metadata and the current
 * operating system.
 */
public final class NativeLibraryResolver {
    private static final ConcurrentMap<String, Path> EXTRACTED_LIBRARIES = new ConcurrentHashMap<>();

    private NativeLibraryResolver() {
    }

    /**
     * Resolves and verifies the expected native library path.
     *
     * @param apiType annotated API interface
     * @param module module annotation
     * @return resolved library path
     */
    public static String resolve(Class<?> apiType, CppModule module) {
        Path path = locate(apiType, module);

        if (!Files.isRegularFile(path)) {
            String libraryName = effectiveLibraryName(apiType, module);
            throw new CppBridgeException(
                    "Native library was not found: " + path.toAbsolutePath().normalize() + "\n" +
                            "Expected libraryName='" + libraryName + "'. " +
                            "Classpath resource: " + resourcePath(apiType, module) + ". " +
                            "Run `mvn package` in the module that owns src/main/cpp first, " +
                            "or pass an explicit path: CppBridge.load(Api.class, \"target/native/...\")."
            );
        }

        return path.toString();
    }

    /**
     * Finds a library in the configured directory or extracts the matching
     * platform resource from the API's class loader. An explicit library path
     * never falls back to the classpath. If neither location exists, returns
     * the expected filesystem path so inspection can report missing bindings.
     *
     * @param apiType annotated API interface
     * @param module module annotation
     * @return local path to the library, or the expected missing path
     */
    public static Path locate(Class<?> apiType, CppModule module) {
        Path expected = expectedPath(apiType, module);
        if (!module.libraryPath().isBlank() || Files.exists(expected)) {
            return expected;
        }
        String resource = resourcePath(apiType, module);
        try {
            ClassLoader loader = apiType.getClassLoader();
            List<URL> matches = Collections.list(loader == null
                    ? ClassLoader.getSystemResources(resource) : loader.getResources(resource));
            if (matches.isEmpty()) {
                return expected;
            }
            if (matches.size() > 1) {
                throw new CppBridgeException("Multiple native libraries found for " + resource + ": " + matches
                        + ". Remove the duplicate dependency or pass an explicit library path.");
            }
            URL source = matches.getFirst();
            return EXTRACTED_LIBRARIES.computeIfAbsent(source.toExternalForm(),
                    ignored -> extract(source, expected.getFileName().toString()));
        } catch (IOException exception) {
            throw new CppBridgeException("Cannot locate native resource: " + resource, exception);
        }
    }

    private static String resourcePath(Class<?> apiType, CppModule module) {
        NativePlatform platform = NativePlatform.detect();
        return platform.resourceDirectory() + "/" + platform.libraryFileName(effectiveLibraryName(apiType, module));
    }

    private static Path extract(URL source, String fileName) {
        Path directory = null;
        Path library = null;
        try {
            URLConnection connection = source.openConnection();
            // Release the source JAR after extraction, independently of its class loader.
            connection.setUseCaches(false);
            try (InputStream input = connection.getInputStream()) {
                directory = Files.createTempDirectory("cppbridge-");
                library = directory.resolve(fileName);
                Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
                directory.toFile().deleteOnExit();
                library.toFile().deleteOnExit();
                return library;
            }
        } catch (IOException exception) {
            try {
                if (library != null) Files.deleteIfExists(library);
                if (directory != null) Files.deleteIfExists(directory);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new CppBridgeException("Cannot extract native library from " + source
                    + ". Check java.io.tmpdir permissions and available disk space.", exception);
        }
    }

    /**
     * Computes the expected native library path without checking whether it
     * exists.
     *
     * @param apiType annotated API interface
     * @param module module annotation
     * @return expected library path
     */
    public static Path expectedPath(Class<?> apiType, CppModule module) {
        if (module.libraryPath() != null && !module.libraryPath().isBlank()) {
            return Path.of(module.libraryPath()).normalize();
        }

        String fileName = NativePlatform.detect().libraryFileName(effectiveLibraryName(apiType, module));
        return Path.of(module.outputDirectory(), fileName).normalize();
    }

    /**
     * Returns the configured logical library name or falls back to the API type
     * simple name.
     *
     * @param apiType annotated API interface
     * @param module module annotation
     * @return logical native library name
     */
    public static String effectiveLibraryName(Class<?> apiType, CppModule module) {
        String libraryName = module.libraryName();
        if (libraryName == null || libraryName.isBlank()) {
            libraryName = apiType.getSimpleName();
        }
        return libraryName;
    }
}
