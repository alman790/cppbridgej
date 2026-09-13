package dev.cppbridge.example;

import dev.cppbridge.CppBridge;
import dev.cppbridge.annotations.*;
import dev.cppbridge.memory.NativeDoubleArray;
import dev.cppbridge.memory.NativeHandle;

import java.lang.foreign.*;

/** Struct values and ownership of a C++ class containing STL containers. */
public final class RichTypesDemo {
    private RichTypesDemo() {}

    @CppStruct
    public record Point(double x, double y) {}

    @CppCallback
    public interface Transform {
        double apply(double value);
    }

    @CppModule(libraryName = "fastmath")
    public interface Api {
        @CppFunction("rich_translate")
        Point translate(Point point, Point offset);

        @CppStatus(error = "rich_error")
        void history_create(@CppString(maxBytes = 512) String title, MemorySegment output);

        void history_destroy(MemorySegment history);

        @CppString(maxBytes = 512)
        String history_title(MemorySegment history);

        @CppStatus(error = "rich_error")
        void history_add(MemorySegment history, double value);

        @CppStatus(error = "rich_error")
        void history_map(MemorySegment history, Transform operation);

        @CppStatus(error = "rich_error")
        void history_average(MemorySegment history, NativeDoubleArray output);
    }

    /** A Java facade with deterministic destruction of the matching native object. */
    public static final class History implements AutoCloseable {
        private final Api api;
        private final NativeHandle handle;

        public History(Api api, String title) {
            this.api = api;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment output = arena.allocate(ValueLayout.ADDRESS);
                api.history_create(title, output);
                handle = NativeHandle.own(output.get(ValueLayout.ADDRESS, 0), api::history_destroy);
            }
        }

        public String title() {
            return handle.use(api::history_title);
        }

        public void add(double value) {
            handle.use(
                    pointer -> {
                        api.history_add(pointer, value);
                        return null;
                    });
        }

        public void map(Transform operation) {
            handle.use(
                    pointer -> {
                        api.history_map(pointer, operation);
                        return null;
                    });
        }

        public double average() {
            return handle.use(
                    pointer -> {
                        try (NativeDoubleArray result = NativeDoubleArray.allocate(1)) {
                            api.history_average(pointer, result);
                            return result.get(0);
                        }
                    });
        }

        @Override
        public void close() {
            handle.close();
        }
    }

    public static void run() {
        Api api = CppBridge.load(Api.class);
        System.out.println("translated point = " + api.translate(new Point(1, 2), new Point(3, 4)));
        try (History history = new History(api, "История")) {
            history.add(2);
            history.add(4);
            history.add(6);
            history.map(value -> value + 1);
            System.out.println("history title = " + history.title());
            System.out.println("history average = " + history.average());
        }
    }
}
