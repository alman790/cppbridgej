package dev.cppbridge;

import dev.cppbridge.annotations.CppModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CppBridgeValidationTest {
    @Test
    void loadRequiresInterface() {
        CppBridgeException exception = assertThrows(
                CppBridgeException.class,
                () -> CppBridge.load(NotAnInterface.class)
        );

        assertTrue(exception.getMessage().contains("can load only interfaces"));
    }

    @Test
    void loadRejectsPlannedWasmBackend() {
        CppBridgeException exception = assertThrows(
                CppBridgeException.class,
                () -> CppBridge.load(WasmApi.class)
        );

        assertTrue(exception.getMessage().contains("WASM backend is planned"));
    }

    @Test
    void loadWithExplicitPathStillRequiresCppModule() {
        CppBridgeException exception = assertThrows(
                CppBridgeException.class,
                () -> CppBridge.load(NotAModule.class, "target/native/libmissing.so")
        );

        assertTrue(exception.getMessage().contains("Missing @CppModule"));
    }

    static final class NotAnInterface {
    }

    interface NotAModule {
        int answer();
    }

    @CppModule(mode = BuildMode.WASM)
    interface WasmApi {
        int answer();
    }
}
