package dev.cppbridge.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CppCompilerCommandBuilderTest {
    @Test
    void buildsLinuxCommand() throws Exception {
        List<String> command = CppCompilerCommandBuilder.build(
                Platform.LINUX,
                "",
                "O3",
                "c++20",
                List.of("-DEXTRA=1"),
                List.of(Path.of("src/a.cpp")),
                Path.of("target/native/liba.so")
        );

        assertEquals("g++", command.getFirst());
        assertTrue(command.contains("-O3"));
        assertTrue(command.contains("-std=c++20"));
        assertTrue(command.contains("-fPIC"));
        assertTrue(command.contains("-DEXTRA=1"));
    }

    @Test
    void buildsMacosCommand() throws Exception {
        List<String> command = CppCompilerCommandBuilder.build(
                Platform.MACOS,
                "clang++",
                "O0",
                "c++23",
                List.of(),
                List.of(Path.of("src/a.cpp")),
                Path.of("target/native/liba.dylib")
        );

        assertEquals("clang++", command.getFirst());
        assertTrue(command.contains("-O0"));
        assertTrue(command.contains("-std=c++23"));
    }

    @Test
    void buildsWindowsCommandWithValidatedMappings() throws Exception {
        List<String> command = CppCompilerCommandBuilder.build(
                Platform.WINDOWS,
                "",
                "O3",
                "c++23",
                List.of("/DCPPBRIDGE_TEST"),
                List.of(Path.of("src\\a.cpp")),
                Path.of("target\\native\\a.dll")
        );

        assertEquals("cl", command.getFirst());
        assertTrue(command.contains("/O2"));
        assertTrue(command.contains("/std:c++latest"));
        assertTrue(command.contains("/LD"));
        assertTrue(command.contains("/EHsc"));
        assertTrue(command.contains("/DCPPBRIDGE_TEST"));
    }

    @Test
    void rejectsUnsupportedCompilerConfiguration() {
        assertThrows(MojoExecutionException.class, () -> CppCompilerCommandBuilder.build(
                Platform.WINDOWS,
                "",
                "Ofast",
                "c++20",
                List.of(),
                List.of(Path.of("a.cpp")),
                Path.of("a.dll")
        ));

        assertThrows(MojoExecutionException.class, () -> CppCompilerCommandBuilder.build(
                Platform.LINUX,
                "",
                "O2",
                "gnu++20",
                List.of(),
                List.of(Path.of("a.cpp")),
                Path.of("liba.so")
        ));
    }
}
