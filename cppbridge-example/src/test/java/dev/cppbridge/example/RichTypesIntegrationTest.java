package dev.cppbridge.example;

import static org.junit.jupiter.api.Assertions.*;

import dev.cppbridge.CppBridge;
import dev.cppbridge.CppBridgeException;

import org.junit.jupiter.api.Test;

class RichTypesIntegrationTest {
    @Test
    void ownsCppClassWithStlAndTranslatesExceptions() {
        RichTypesDemo.Api api = CppBridge.load(RichTypesDemo.Api.class);
        assertEquals(
                new RichTypesDemo.Point(4, 6),
                api.translate(new RichTypesDemo.Point(1, 2), new RichTypesDemo.Point(3, 4)));
        RichTypesDemo.History history = new RichTypesDemo.History(api, "История 🌍");
        try (history) {
            assertEquals("История 🌍", history.title());
            assertTrue(
                    assertThrows(CppBridgeException.class, history::average)
                            .getMessage()
                            .contains("History is empty"));
            history.add(2);
            history.add(4);
            history.add(6);
            history.map(value -> value + 1);
            assertEquals(5, history.average());
            assertThrows(CppBridgeException.class, () -> history.add(Double.NaN));
            assertEquals(5, history.average());
        }
        assertDoesNotThrow(history::close);
        assertThrows(CppBridgeException.class, history::average);
    }
}
