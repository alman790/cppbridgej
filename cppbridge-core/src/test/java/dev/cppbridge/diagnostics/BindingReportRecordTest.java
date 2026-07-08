package dev.cppbridge.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingReportRecordTest {
    @Test
    void reportDefensivelyCopiesEntries() {
        List<BindingReportEntry> entries = new ArrayList<>();
        entries.add(new BindingReportEntry("int sum()", "sum", "int sum()", BindingStatus.OK, null));

        BindingReport report = new BindingReport("Api", "NATIVE", "lib.so", true, entries);
        entries.clear();

        assertEquals(1, report.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> report.entries().add(
                new BindingReportEntry("void x()", "x", "void x()", BindingStatus.OK, "")
        ));
        assertTrue(report.isHealthy());
        assertEquals(report.toText(), report.toString());
    }

    @Test
    void reportIsUnhealthyForMissingLibrariesOrBadEntries() {
        BindingReport missingLibrary = new BindingReport("Api", "NATIVE", "lib.so", false, List.of());
        BindingReport badEntry = new BindingReport("Api", "NATIVE", "lib.so", true, List.of(
                new BindingReportEntry("int x()", "x", "int x()", BindingStatus.MISSING_SYMBOL, "missing")
        ));

        assertFalse(missingLibrary.isHealthy());
        assertFalse(badEntry.isHealthy());
        assertTrue(badEntry.toText().contains("missing"));
    }

    @Test
    void constructorsRejectRequiredNulls() {
        assertThrows(NullPointerException.class, () -> new BindingReport(null, "NATIVE", "lib.so", true, List.of()));
        assertThrows(NullPointerException.class, () -> new BindingReport("Api", null, "lib.so", true, List.of()));
        assertThrows(NullPointerException.class, () -> new BindingReport("Api", "NATIVE", null, true, List.of()));
        assertThrows(NullPointerException.class, () -> new BindingReport("Api", "NATIVE", "lib.so", true, null));
        assertThrows(NullPointerException.class, () -> new BindingReportEntry(null, "x", "x()", BindingStatus.OK, ""));
        assertThrows(NullPointerException.class, () -> new BindingReportEntry("x()", null, "x()", BindingStatus.OK, ""));
        assertThrows(NullPointerException.class, () -> new BindingReportEntry("x()", "x", null, BindingStatus.OK, ""));
        assertThrows(NullPointerException.class, () -> new BindingReportEntry("x()", "x", "x()", null, ""));
    }
}
