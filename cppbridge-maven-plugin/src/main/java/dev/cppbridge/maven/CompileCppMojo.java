package dev.cppbridge.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Maven goal that compiles C++ sources from {@code src/main/cpp} into a native
 * shared library.
 *
 * <p>The goal also inspects exported symbols and can fail the build when a
 * configured expected symbol is missing. Reports are written to
 * {@code target/cppbridge} by default.</p>
 */
@Mojo(name = "compile-cpp", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public final class CompileCppMojo extends AbstractMojo {
    /** Directory containing C++ source files. */
    @Parameter(defaultValue = "${project.basedir}/src/main/cpp")
    private File sourceDirectory;

    /** Directory where the compiled shared library is written. */
    @Parameter(defaultValue = "${project.build.directory}/native")
    private File outputDirectory;

    /** Directory where build reports and symbol reports are written. */
    @Parameter(defaultValue = "${project.build.directory}/cppbridge")
    private File reportDirectory;

    /** Project directory used to resolve relative compiler and linker arguments. */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File projectDirectory;

    /** Java output directory receiving the packaged native resource. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File classesDirectory;

    /** Include the current platform library in the project's JAR. */
    @Parameter(property = "cppbridge.packageNative", defaultValue = "true")
    private boolean packageNative;

    /** Header search directories. Paths are resolved relative to the project. */
    @Parameter
    private List<File> includeDirectories;

    /** Linker arguments, placed after source files so static libraries resolve correctly. */
    @Parameter
    private List<String> extraLinkerArgs;

    /** Maximum runtime for each compiler or symbol-inspection command. */
    @Parameter(property = "cppbridge.commandTimeoutSeconds", defaultValue = "300")
    private long commandTimeoutSeconds;

    /** Logical native library name without platform prefix or extension. */
    @Parameter(defaultValue = "${project.artifactId}")
    private String libraryName;

    /** Optional compiler executable. If empty, the platform default is used. */
    @Parameter(property = "cppbridge.compiler", defaultValue = "")
    private String compiler;

    /** Compiler optimization level, for example {@code O2} or {@code O3}. */
    @Parameter(property = "cppbridge.optimizationLevel", defaultValue = "O3")
    private String optimizationLevel;

    /** C++ language standard passed to the compiler. */
    @Parameter(property = "cppbridge.cppStandard", defaultValue = "c++20")
    private String cppStandard;

    /** Extra compiler arguments, for example: -march=native, -ffast-math, /arch:AVX2. */
    @Parameter
    private List<String> extraCompilerArgs;

    /** Symbols that must be exported by the resulting native library. */
    @Parameter
    private List<String> expectedSymbols;

    /** Fail the Maven build if any configured expectedSymbols are missing. */
    @Parameter(defaultValue = "true")
    private boolean failOnMissingSymbols;

    /** Generate target/cppbridge/native-build-report.txt and exported-symbols.txt. */
    @Parameter(defaultValue = "true")
    private boolean generateBuildReport;

    /** Skip C++ compilation for this module. */
    @Parameter(property = "cppbridge.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Executes C++ compilation, symbol inspection, and optional symbol
     * validation.
     *
     * @throws MojoExecutionException if compilation or required symbol
     *                                validation fails
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("C++ compilation skipped by configuration.");
            return;
        }
        Path sourceDir = sourceDirectory.toPath();
        if (!Files.exists(sourceDir)) {
            getLog().info("C++ source directory does not exist, skipping: " + sourceDir);
            return;
        }

        List<Path> cppFiles = findCppFiles(sourceDir);
        if (cppFiles.isEmpty()) {
            getLog().info("No C++ files found in: " + sourceDir);
            return;
        }

        try {
            Files.createDirectories(outputDirectory.toPath());
            Files.createDirectories(reportDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create CppBridgeJ output directories", e);
        }

        Platform platform = Platform.detect();
        Path outputLibrary = outputDirectory.toPath().resolve(platform.libraryFileName(libraryName));
        List<String> command = buildCommand(platform, cppFiles, outputLibrary);

        getLog().info("Compiling C++ library: " + outputLibrary);
        getLog().info(String.join(" ", command));

        CommandResult compileResult = runCommand(command);
        if (!compileResult.output().isBlank()) {
            getLog().info(compileResult.output());
        }
        if (compileResult.exitCode() != 0) {
            throw new MojoExecutionException("C++ compiler failed with exit code "
                    + compileResult.exitCode() + "\n" + compileResult.output());
        }

        NativeSymbolReport symbolReport = inspectExportedSymbols(platform, outputLibrary);
        List<String> missingSymbols = findMissingSymbols(platform, symbolReport.normalizedSymbols());

        if (generateBuildReport) {
            writeReports(platform, cppFiles, outputLibrary, command, compileResult, symbolReport, missingSymbols);
        }

        if (symbolReport.inspectionFailed() && expectedSymbols != null && !expectedSymbols.isEmpty()) {
            throw new MojoExecutionException(symbolReport.message());
        }

        if (!missingSymbols.isEmpty()) {
            String message = "Native library is missing expected exported symbols: " + String.join(", ", missingSymbols)
                    + "\nSee: " + reportDirectory.toPath().resolve("native-build-report.txt").toAbsolutePath();
            if (failOnMissingSymbols) {
                throw new MojoExecutionException(message);
            }
            getLog().warn(message);
        } else if (expectedSymbols != null && !expectedSymbols.isEmpty()) {
            getLog().info("CppBridgeJ expected-symbol validation passed: " + expectedSymbols.size() + " symbol(s).");
        }

        if (packageNative) {
            Path resource = classesDirectory.toPath().resolve(platform.resourceDirectory())
                    .resolve(outputLibrary.getFileName());
            try {
                Files.createDirectories(resource.getParent());
                Files.copy(outputLibrary, resource, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new MojoExecutionException("Cannot package native library: " + resource, exception);
            }
            getLog().info("Packaged native library: " + resource);
        }
    }

    private List<Path> findCppFiles(Path sourceDir) throws MojoExecutionException {
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot scan C++ source directory: " + sourceDir, e);
        }
    }

    private List<String> buildCommand(Platform platform, List<Path> cppFiles, Path outputLibrary) throws MojoExecutionException {
        return CppCompilerCommandBuilder.build(
                platform,
                compiler,
                optimizationLevel,
                cppStandard,
                extraCompilerArgs,
                cppFiles,
                outputLibrary,
                includeDirectories == null ? List.of() : includeDirectories.stream()
                        .map(File::toPath)
                        .map(path -> path.isAbsolute() ? path : projectDirectory.toPath().resolve(path))
                        .toList(),
                extraLinkerArgs
        );
    }

    private CommandResult runCommand(List<String> command) throws MojoExecutionException {
        return CommandRunner.run(command, projectDirectory.toPath(), commandTimeoutSeconds);
    }

    private NativeSymbolReport inspectExportedSymbols(Platform platform, Path outputLibrary) {
        return NativeSymbolInspector.inspect(platform, outputLibrary, this::runCommand);
    }

    private List<String> findMissingSymbols(Platform platform, Set<String> normalizedSymbols) {
        if (expectedSymbols == null || expectedSymbols.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String expectedSymbol : expectedSymbols) {
            if (expectedSymbol == null || expectedSymbol.isBlank()) {
                continue;
            }
            String normalizedExpected = NativeSymbolInspector.normalizeSymbol(platform, expectedSymbol);
            if (!normalizedSymbols.contains(normalizedExpected)) {
                missing.add(expectedSymbol);
            }
        }
        return missing;
    }

    private void writeReports(
            Platform platform,
            List<Path> cppFiles,
            Path outputLibrary,
            List<String> command,
            CommandResult compileResult,
            NativeSymbolReport symbolReport,
            List<String> missingSymbols
    ) throws MojoExecutionException {
        Path reportDir = reportDirectory.toPath();
        Path buildReport = reportDir.resolve("native-build-report.txt");
        Path exportedSymbolsFile = reportDir.resolve("exported-symbols.txt");
        Path rawSymbolsFile = reportDir.resolve("exported-symbols-raw.txt");
        Path missingSymbolsFile = reportDir.resolve("missing-symbols.txt");

        StringBuilder report = new StringBuilder();
        report.append("CppBridgeJ native build report\n");
        report.append("Generated at: ").append(Instant.now()).append('\n');
        report.append("Platform: ").append(platform).append('\n');
        report.append("Library name: ").append(libraryName).append('\n');
        report.append("Output library: ").append(outputLibrary.toAbsolutePath()).append('\n');
        report.append("Source directory: ").append(sourceDirectory.toPath().toAbsolutePath()).append('\n');
        report.append("Compiler command: ").append(String.join(" ", command)).append('\n');
        report.append("Compiler exit code: ").append(compileResult.exitCode()).append('\n');
        report.append('\n');

        report.append("C++ sources:\n");
        for (Path cppFile : cppFiles) {
            report.append("- ").append(cppFile.toAbsolutePath()).append('\n');
        }
        report.append('\n');

        report.append("Expected symbols:\n");
        if (expectedSymbols == null || expectedSymbols.isEmpty()) {
            report.append("- <none configured>\n");
        } else {
            for (String expectedSymbol : expectedSymbols) {
                report.append("- ").append(expectedSymbol).append('\n');
            }
        }
        report.append('\n');

        report.append("Symbol inspection command: ").append(String.join(" ", symbolReport.command())).append('\n');
        if (!symbolReport.message().isBlank()) {
            report.append("Symbol inspection note: ").append(symbolReport.message()).append('\n');
        }
        report.append("Exported symbols detected: ").append(symbolReport.normalizedSymbols().size()).append('\n');
        report.append("Missing expected symbols: ").append(missingSymbols.size()).append('\n');
        for (String missingSymbol : missingSymbols) {
            report.append("- MISSING: ").append(missingSymbol).append('\n');
        }

        try {
            Files.writeString(buildReport, report.toString(), StandardCharsets.UTF_8);
            Files.writeString(exportedSymbolsFile, String.join(System.lineSeparator(), symbolReport.normalizedSymbols()), StandardCharsets.UTF_8);
            Files.writeString(rawSymbolsFile, symbolReport.rawOutput(), StandardCharsets.UTF_8);
            Files.writeString(missingSymbolsFile, String.join(System.lineSeparator(), missingSymbols), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write CppBridgeJ build reports to " + reportDir, e);
        }

        getLog().info("CppBridgeJ native build report: " + buildReport.toAbsolutePath());
    }

}
