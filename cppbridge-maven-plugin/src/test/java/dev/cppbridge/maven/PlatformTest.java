package dev.cppbridge.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformTest {
    @Test
    void packagedResourcePathsMatchTheRuntimeOnEveryPlatform() {
        String original = System.getProperty("os.arch");
        try {
            for (String arch : new String[]{"amd64", "x86_64", "arm64", "aarch64", "i386", "riscv64"}) {
                System.setProperty("os.arch", arch);
                for (Platform platform : Platform.values()) {
                    var runtime = dev.cppbridge.runtime.NativePlatform.valueOf(platform.name());
                    assertEquals(runtime.resourceDirectory(), platform.resourceDirectory());
                    assertEquals(runtime.libraryFileName("test-library"), platform.libraryFileName("test-library"));
                }
            }
        } finally {
            System.setProperty("os.arch", original);
        }
    }

    @Test
    void detectMapsCommonOperatingSystems() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertEquals(Platform.MACOS, Platform.detect());
            System.setProperty("os.name", "Windows Server 2022");
            assertEquals(Platform.WINDOWS, Platform.detect());
            System.setProperty("os.name", "Linux");
            assertEquals(Platform.LINUX, Platform.detect());
            System.setProperty("os.name", "FreeBSD");
            assertEquals(Platform.LINUX, Platform.detect());
        } finally {
            if (original == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", original);
            }
        }
    }

    @Test
    void libraryFileNameSanitizesUserInput() {
        assertEquals("libfast_math.dylib", Platform.MACOS.libraryFileName("fast-math"));
        assertEquals("libfast_math.so", Platform.LINUX.libraryFileName("fast.math"));
        assertEquals("fast_math.dll", Platform.WINDOWS.libraryFileName("fast math"));
    }

    @Test
    void defaultCompilerIsPlatformSpecific() {
        assertEquals("clang++", Platform.MACOS.defaultCompiler());
        assertEquals("g++", Platform.LINUX.defaultCompiler());
        assertEquals("cl", Platform.WINDOWS.defaultCompiler());
    }
}
