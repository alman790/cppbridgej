package dev.cppbridge.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSymbolInspectorTest {
    @Test
    void linuxParserKeepsOnlyDefinedExternalDynamicSymbols() {
        String output = """
                0000000000001100 T exported_symbol
                                 U missing_symbol
                0000000000004010 D exported_data
                0000000000001040 t local_helper
                """;

        assertEquals(Set.of("exported_symbol", "exported_data"), NativeSymbolInspector.parse(Platform.LINUX, output));
    }

    @Test
    void macosParserKeepsDefinedExternalSymbolsAndNormalizesLeadingUnderscore() {
        String output = """
                0000000000000590 T _exported_symbol
                                 U _missing_symbol
                                 U dyld_stub_binder
                """;

        Set<String> raw = NativeSymbolInspector.parse(Platform.MACOS, output);

        assertEquals(Set.of("_exported_symbol"), raw);
        assertEquals("exported_symbol", NativeSymbolInspector.normalizeSymbol(Platform.MACOS, "_exported_symbol"));
        assertEquals("__Z17decorated_symbolv", NativeSymbolInspector.normalizeSymbol(Platform.LINUX, "__Z17decorated_symbolv"));
    }

    @Test
    void windowsParserReadsRealisticDumpbinExports() {
        String output = """
                ordinal hint RVA      name

                      1    0 00001000 exported_symbol = exported_symbol
                      2    1 00001020 decorated_function@@YAHH@Z
                Summary
                """;

        assertEquals(
                Set.of("exported_symbol", "decorated_function@@YAHH@Z"),
                NativeSymbolInspector.parse(Platform.WINDOWS, output)
        );
    }

    @Test
    void malformedAndEmptyOutputProduceNoSymbols() {
        assertTrue(NativeSymbolInspector.parse(Platform.LINUX, "").isEmpty());
        assertTrue(NativeSymbolInspector.parse(Platform.MACOS, "not nm output").isEmpty());
        assertTrue(NativeSymbolInspector.parse(Platform.WINDOWS, "ordinal hint RVA name").isEmpty());
    }

    @Test
    void missingInspectionExecutableIsDistinctFailure() {
        NativeSymbolReport report = NativeSymbolInspector.inspect(
                Platform.LINUX,
                Path.of("libmissing.so"),
                command -> {
                    throw new MojoExecutionException("Cannot start command: " + String.join(" ", command));
                }
        );

        assertTrue(report.inspectionFailed());
        assertTrue(report.message().contains("Ensure nm is installed"));
        assertTrue(report.normalizedSymbols().isEmpty());
    }

    @Test
    void commandFailureIsDistinctFromMissingSymbol() {
        NativeSymbolReport report = NativeSymbolInspector.inspect(
                Platform.WINDOWS,
                Path.of("missing.dll"),
                command -> new CommandResult(1, "dumpbin failed")
        );

        assertTrue(report.inspectionFailed());
        assertTrue(report.message().contains("exit code 1"));
        assertFalse(report.message().contains("missing expected"));
    }
}
