package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLibraryResolverTest {
    @Test
    void explicitLibraryPathWins() {
        CppModule module = ExplicitPathApi.class.getAnnotation(CppModule.class);

        assertEquals(Path.of(module.libraryPath()).normalize(), NativeLibraryResolver.expectedPath(ExplicitPathApi.class, module));
    }

    @Test
    void blankLibraryNameFallsBackToInterfaceSimpleName() {
        CppModule module = DefaultNameApi.class.getAnnotation(CppModule.class);

        assertEquals("DefaultNameApi", NativeLibraryResolver.effectiveLibraryName(DefaultNameApi.class, module));
    }

    @Test
    void missingLibraryMessageExplainsHowToFix() {
        CppModule module = DefaultNameApi.class.getAnnotation(CppModule.class);

        CppBridgeException exception = assertThrows(
                CppBridgeException.class,
                () -> NativeLibraryResolver.resolve(DefaultNameApi.class, module)
        );

        assertTrue(exception.getMessage().contains("Native library was not found"));
        assertTrue(exception.getMessage().contains("mvn package"));
        assertTrue(exception.getMessage().contains("CppBridge.load"));
    }

    @Test
    void platformDetectMapsCommonOsNames() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertEquals(NativePlatform.WINDOWS, NativePlatform.detect());
            System.setProperty("os.name", "Mac OS X");
            assertEquals(NativePlatform.MACOS, NativePlatform.detect());
            System.setProperty("os.name", "Linux");
            assertEquals(NativePlatform.LINUX, NativePlatform.detect());
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }

    @Test
    void platformBuildsExpectedLibraryNames() {
        assertEquals("libfast_math.dylib", NativePlatform.MACOS.libraryFileName("fast-math"));
        assertEquals("libfast_math.so", NativePlatform.LINUX.libraryFileName("fast.math"));
        assertEquals("fast_math.dll", NativePlatform.WINDOWS.libraryFileName("fast math"));
    }

    @CppModule(libraryPath = "target/native/libcustom.so")
    interface ExplicitPathApi {
        int answer();
    }

    @CppModule(libraryName = "", outputDirectory = "target/native")
    interface DefaultNameApi {
        int answer();
    }
}
