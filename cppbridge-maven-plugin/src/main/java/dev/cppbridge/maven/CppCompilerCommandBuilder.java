package dev.cppbridge.maven;

import org.apache.maven.plugin.MojoExecutionException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class CppCompilerCommandBuilder {
    private CppCompilerCommandBuilder() {
    }

    static List<String> build(
            Platform platform,
            String compiler,
            String optimizationLevel,
            String cppStandard,
            List<String> extraCompilerArgs,
            List<Path> cppFiles,
            Path outputLibrary
    ) throws MojoExecutionException {
        if (platform == Platform.WINDOWS) {
            return buildMsvcCommand(compiler, optimizationLevel, cppStandard, extraCompilerArgs, cppFiles, outputLibrary);
        }
        return buildUnixCommand(platform, compiler, optimizationLevel, cppStandard, extraCompilerArgs, cppFiles, outputLibrary);
    }

    private static List<String> buildUnixCommand(
            Platform platform,
            String compiler,
            String optimizationLevel,
            String cppStandard,
            List<String> extraCompilerArgs,
            List<Path> cppFiles,
            Path outputLibrary
    ) throws MojoExecutionException {
        validateOptimizationLevel(optimizationLevel);
        validateUnixStandard(cppStandard);

        List<String> command = new ArrayList<>();
        command.add(selectCompiler(platform, compiler));
        command.add("-" + optimizationLevel);
        command.add("-std=" + cppStandard);
        command.add("-shared");
        command.add("-fPIC");
        addExtraCompilerArgs(command, extraCompilerArgs);
        for (Path cppFile : cppFiles) {
            command.add(cppFile.toAbsolutePath().toString());
        }
        command.add("-o");
        command.add(outputLibrary.toAbsolutePath().toString());
        return command;
    }

    private static List<String> buildMsvcCommand(
            String compiler,
            String optimizationLevel,
            String cppStandard,
            List<String> extraCompilerArgs,
            List<Path> cppFiles,
            Path outputLibrary
    ) throws MojoExecutionException {
        List<String> command = new ArrayList<>();
        command.add(compiler == null || compiler.isBlank() ? Platform.WINDOWS.defaultCompiler() : compiler);
        command.add("/nologo");
        command.add(msvcOptimizationFlag(optimizationLevel));
        command.add(msvcStandardFlag(cppStandard));
        command.add("/LD");
        command.add("/EHsc");
        addExtraCompilerArgs(command, extraCompilerArgs);
        for (Path cppFile : cppFiles) {
            command.add(cppFile.toAbsolutePath().toString());
        }
        command.add("/Fe:" + outputLibrary.toAbsolutePath());
        return command;
    }

    private static String selectCompiler(Platform platform, String compiler) {
        return compiler == null || compiler.isBlank() ? platform.defaultCompiler() : compiler;
    }

    private static void addExtraCompilerArgs(List<String> command, List<String> extraCompilerArgs) {
        if (extraCompilerArgs == null || extraCompilerArgs.isEmpty()) {
            return;
        }
        for (String arg : extraCompilerArgs) {
            if (arg != null && !arg.isBlank()) {
                command.add(arg);
            }
        }
    }

    private static void validateOptimizationLevel(String optimizationLevel) throws MojoExecutionException {
        switch (optimizationLevel) {
            case "O0", "O1", "O2", "O3" -> {
                return;
            }
            default -> throw new MojoExecutionException(
                    "Unsupported optimizationLevel '" + optimizationLevel + "'. Supported values: O0, O1, O2, O3."
            );
        }
    }

    private static void validateUnixStandard(String cppStandard) throws MojoExecutionException {
        switch (cppStandard) {
            case "c++17", "c++20", "c++23" -> {
                return;
            }
            default -> throw new MojoExecutionException(
                    "Unsupported cppStandard '" + cppStandard + "'. Supported values: c++17, c++20, c++23."
            );
        }
    }

    private static String msvcOptimizationFlag(String optimizationLevel) throws MojoExecutionException {
        return switch (optimizationLevel) {
            case "O0" -> "/Od";
            case "O1" -> "/O1";
            case "O2", "O3" -> "/O2";
            default -> throw new MojoExecutionException(
                    "Unsupported optimizationLevel '" + optimizationLevel + "' for MSVC. Supported values: O0, O1, O2, O3."
            );
        };
    }

    private static String msvcStandardFlag(String cppStandard) throws MojoExecutionException {
        return switch (cppStandard) {
            case "c++17" -> "/std:c++17";
            case "c++20" -> "/std:c++20";
            case "c++23" -> "/std:c++latest";
            default -> throw new MojoExecutionException(
                    "Unsupported cppStandard '" + cppStandard + "' for MSVC. Supported values: c++17, c++20, c++23."
            );
        };
    }
}
