package it.cppbridge;

import dev.cppbridge.CppBridge;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsumerLoadTest {
    @Test
    void loadsGeneratedLibraryAsExternalConsumer() {
        ConsumerApi api = CppBridge.load(ConsumerApi.class, libraryPath().toString());

        assertEquals(123, api.answer());
    }

    private static Path libraryPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String name = os.contains("win") ? "consumer.dll" : os.contains("mac") || os.contains("darwin")
                ? "libconsumer.dylib"
                : "libconsumer.so";
        return Path.of("target", "native", name);
    }

    @CppModule(libraryName = "consumer")
    interface ConsumerApi {
        @CppFunction("answer_value")
        int answer();
    }
}
