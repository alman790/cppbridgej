package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativeLibraryPackagingTest {
    @TempDir
    Path directory;

    @Test
    void extractsAndReusesLibraryFromTheApiClassLoader() throws Exception {
        Path jar = archive("api.jar", "");
        try (URLClassLoader loader = loader(jar)) {
            Class<?> api = loader.loadClass("fixture.PackagedApi");
            CppModule module = api.getAnnotation(CppModule.class);
            Path extracted = NativeLibraryResolver.locate(api, module);

            assertNotEquals(NativeLibraryResolver.expectedPath(api, module), extracted);
            assertEquals("native fixture", Files.readString(extracted));
            assertEquals(extracted, NativeLibraryResolver.locate(api, module));
            assertEquals(extracted.toString(), NativeLibraryResolver.resolve(api, module));
        }
    }

    @Test
    void rejectsAmbiguousClasspathLibraries() throws Exception {
        Path first = archive("first.jar", "");
        Path second = archive("second.jar", "");
        try (URLClassLoader loader = loader(first, second)) {
            Class<?> api = loader.loadClass("fixture.PackagedApi");
            CppBridgeException error = assertThrows(CppBridgeException.class,
                    () -> NativeLibraryResolver.locate(api, api.getAnnotation(CppModule.class)));
            assertTrue(error.getMessage().contains("Multiple native libraries"));
            assertTrue(error.getMessage().contains("explicit library path"));
        }
    }

    @Test
    void explicitMissingPathNeverFallsBackToTheClasspath() throws Exception {
        Path missing = directory.resolve("missing-library");
        Path jar = archive("explicit.jar", ", libraryPath = \"" + javaString(missing) + "\"");
        try (URLClassLoader loader = loader(jar)) {
            Class<?> api = loader.loadClass("fixture.PackagedApi");
            CppModule module = api.getAnnotation(CppModule.class);
            assertEquals(missing, NativeLibraryResolver.locate(api, module));
            assertThrows(CppBridgeException.class, () -> NativeLibraryResolver.resolve(api, module));
        }
    }

    @Test
    void developmentLibraryTakesPrecedenceOverPackagedLibrary() throws Exception {
        Path jar = archive("local.jar", "");
        try (URLClassLoader loader = loader(jar)) {
            Class<?> api = loader.loadClass("fixture.PackagedApi");
            CppModule module = api.getAnnotation(CppModule.class);
            Path local = NativeLibraryResolver.expectedPath(api, module);
            Files.createDirectories(local.getParent());
            Files.writeString(local, "development build");
            assertEquals(local, NativeLibraryResolver.locate(api, module));
        }
    }

    @Test
    void normalizesCommonCpuAliases() {
        String original = System.getProperty("os.arch");
        try {
            for (String arch : new String[]{"amd64", "x86_64", "x64"}) {
                System.setProperty("os.arch", arch);
                assertEquals("META-INF/cppbridge/linux-x86_64", NativePlatform.LINUX.resourceDirectory());
            }
            for (String arch : new String[]{"arm64", "aarch64"}) {
                System.setProperty("os.arch", arch);
                assertEquals("META-INF/cppbridge/macos-aarch64", NativePlatform.MACOS.resourceDirectory());
            }
            System.setProperty("os.arch", "i686");
            assertEquals("META-INF/cppbridge/windows-x86", NativePlatform.WINDOWS.resourceDirectory());
            System.setProperty("os.arch", "riscv64");
            assertEquals("META-INF/cppbridge/linux-riscv64", NativePlatform.LINUX.resourceDirectory());
        } finally {
            System.setProperty("os.arch", original);
        }
    }

    private Path archive(String name, String annotationOptions) throws Exception {
        Path classes = Files.createTempDirectory(directory, "classes-");
        Path source = classes.resolve("PackagedApi.java");
        Files.writeString(source, "package fixture;\n"
                + "@dev.cppbridge.annotations.CppModule(libraryName = \"packaged\", outputDirectory = \""
                + javaString(directory.resolve("local")) + "\"" + annotationOptions + ")\n"
                + "public interface PackagedApi { int answer(); }\n");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString());
        assertEquals(0, result, diagnostics.toString());

        Path archive = directory.resolve(name);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry("fixture/PackagedApi.class"));
            Files.copy(classes.resolve("fixture/PackagedApi.class"), jar);
            jar.closeEntry();
            NativePlatform platform = NativePlatform.detect();
            jar.putNextEntry(new JarEntry(platform.resourceDirectory() + "/" + platform.libraryFileName("packaged")));
            jar.write("native fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return archive;
    }

    private URLClassLoader loader(Path... jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        return new URLClassLoader(urls, getClass().getClassLoader());
    }

    private static String javaString(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
