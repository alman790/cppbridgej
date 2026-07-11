package dev.cppbridge.maven;

import org.apache.maven.plugin.MojoExecutionException;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class NativeSymbolInspector {
    private NativeSymbolInspector() {
    }

    static List<String> command(Platform platform, Path outputLibrary) {
        return switch (platform) {
            case WINDOWS -> List.of("dumpbin", "/EXPORTS", outputLibrary.toAbsolutePath().toString());
            case MACOS -> List.of("nm", "-gU", outputLibrary.toAbsolutePath().toString());
            case LINUX -> List.of("nm", "-D", "--defined-only", "-g", outputLibrary.toAbsolutePath().toString());
        };
    }

    static NativeSymbolReport inspect(Platform platform, Path outputLibrary, CommandRunner commandRunner) {
        List<String> command = command(platform, outputLibrary);
        try {
            CommandResult result = commandRunner.run(command);
            if (result.exitCode() != 0) {
                return new NativeSymbolReport(command, result.output(), Set.of(), Set.of(),
                        "Symbol inspection command failed with exit code " + result.exitCode(), true);
            }
            Set<String> rawSymbols = parse(platform, result.output());
            Set<String> normalizedSymbols = new LinkedHashSet<>();
            for (String symbol : rawSymbols) {
                normalizedSymbols.add(normalizeSymbol(platform, symbol));
            }
            return new NativeSymbolReport(command, result.output(), rawSymbols, normalizedSymbols, "", false);
        } catch (MojoExecutionException exception) {
            return new NativeSymbolReport(command, "", Set.of(), Set.of(),
                    "Cannot inspect exported symbols. Ensure "
                            + (platform == Platform.WINDOWS ? "dumpbin" : "nm")
                            + " is installed and on PATH. " + exception.getMessage(), true);
        }
    }

    static Set<String> parse(Platform platform, String output) {
        Set<String> symbols = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            switch (platform) {
                case WINDOWS -> parseDumpbinLine(trimmed, symbols);
                case MACOS -> parseMacosNmLine(trimmed, symbols);
                case LINUX -> parseLinuxNmLine(trimmed, symbols);
            }
        }
        return symbols;
    }

    static String normalizeSymbol(Platform platform, String symbol) {
        String normalized = symbol == null ? "" : symbol.trim();
        if (platform == Platform.MACOS && normalized.startsWith("_")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private static void parseLinuxNmLine(String line, Set<String> symbols) {
        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            return;
        }
        String type = parts[parts.length - 2];
        if (isDefinedExternalNmType(type)) {
            symbols.add(parts[parts.length - 1]);
        }
    }

    private static void parseMacosNmLine(String line, Set<String> symbols) {
        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            return;
        }
        String type = parts[parts.length - 2];
        if (isDefinedExternalNmType(type)) {
            symbols.add(parts[parts.length - 1]);
        }
    }

    private static boolean isDefinedExternalNmType(String type) {
        return type.length() == 1 && type.charAt(0) != 'U' && Character.isUpperCase(type.charAt(0));
    }

    private static void parseDumpbinLine(String line, Set<String> symbols) {
        String[] parts = line.split("\\s+");
        if (parts.length < 4 || !parts[0].chars().allMatch(Character::isDigit)) {
            return;
        }
        String symbol = parts[3];
        if (!symbol.equalsIgnoreCase("ordinal") && !symbol.contains("=")) {
            symbols.add(symbol);
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> command) throws MojoExecutionException;
    }
}
