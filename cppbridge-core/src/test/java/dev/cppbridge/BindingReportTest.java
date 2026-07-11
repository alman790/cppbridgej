package dev.cppbridge;

import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.diagnostics.BindingReport;
import dev.cppbridge.diagnostics.BindingStatus;
import dev.cppbridge.memory.NativeDoubleArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingReportTest {
    @CppModule(libraryName = "missing")
    interface DemoApi {
        @CppFunction("sum_int")
        int sum(int a, int b);

        @CppFunction("multiply_each_double")
        void multiplyEach(NativeDoubleArray values, double factor);

        @CppFunction("bad_return")
        String unsupportedReturn();

        default int javaOnly() {
            return 42;
        }

        static int staticHelper() {
            return 7;
        }
    }

    @Test
    void inspectReportsMissingLibraryWithoutThrowing() {
        BindingReport report = CppBridge.inspect(DemoApi.class, "target/native/no-such-library.dylib");

        assertFalse(report.libraryExists());
        assertFalse(report.isHealthy());
        assertTrue(report.toText().contains("CppBridgeJ binding report"));
        assertTrue(report.toText().contains("int sum_int(int, int)"));
        assertTrue(report.toText().contains("void multiply_each_double(double*, int length, double)"));
        assertEquals(3, report.entries().size());
        assertFalse(report.toText().contains("javaOnly"));
        assertFalse(report.toText().contains("staticHelper"));
        assertTrue(report.entries().stream().anyMatch(entry -> entry.status() == BindingStatus.LIBRARY_NOT_FOUND));
        assertTrue(report.entries().stream().anyMatch(entry -> entry.status() == BindingStatus.UNSUPPORTED_SIGNATURE));
    }

    @Test
    void inspectRequiresCppModuleAnnotation() {
        CppBridgeException exception = org.junit.jupiter.api.Assertions.assertThrows(
                CppBridgeException.class,
                () -> CppBridge.inspect(NotAModule.class)
        );
        assertTrue(exception.getMessage().contains("Missing @CppModule"));
    }

    interface NotAModule {
        int sum(int a, int b);
    }
}
