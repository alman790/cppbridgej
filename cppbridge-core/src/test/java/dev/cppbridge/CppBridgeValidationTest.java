package dev.cppbridge;

import dev.cppbridge.annotations.CppModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;

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

    @Test
    void invalidLibraryHasAnActionableError(@TempDir Path directory) throws Exception {
        Path library = Files.writeString(directory.resolve("not-a-library"), "invalid binary");
        CppBridgeException error = assertThrows(CppBridgeException.class,
                () -> CppBridge.load(ValidApi.class, library.toString()));
        assertTrue(error.getMessage().contains("Cannot open native library"));
        assertTrue(error.getMessage().contains(library.toString()));
    }

    @Test
    void rejectsNullTypesBlankPathsAndSealedInterfaces() {
        assertThrows(NullPointerException.class, () -> CppBridge.load(null));
        assertThrows(CppBridgeException.class, () -> CppBridge.load(ValidApi.class, " "));
        CppBridgeException error = assertThrows(CppBridgeException.class, () -> CppBridge.load(SealedApi.class));
        assertTrue(error.getMessage().contains("non-sealed"));
    }

    @CppModule
    interface ValidApi {
        int answer();
    }

    @CppModule
    sealed interface SealedApi permits SealedImplementation {
        int answer();
    }

    static final class SealedImplementation implements SealedApi {
        public int answer() { return 42; }
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
