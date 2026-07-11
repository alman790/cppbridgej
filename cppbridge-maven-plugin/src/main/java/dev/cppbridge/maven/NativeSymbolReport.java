package dev.cppbridge.maven;

import java.util.List;
import java.util.Set;

record NativeSymbolReport(
        List<String> command,
        String rawOutput,
        Set<String> rawSymbols,
        Set<String> normalizedSymbols,
        String message,
        boolean inspectionFailed
) {
}
