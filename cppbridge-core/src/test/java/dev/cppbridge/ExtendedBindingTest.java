package dev.cppbridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.cppbridge.annotations.*;
import dev.cppbridge.memory.*;

import org.junit.jupiter.api.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ExtendedBindingTest {
    private static Path library;
    private static Api api;

    @BeforeAll
    static void compile() throws Exception {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows"), mac = os.contains("mac");
        String compiler = windows ? "cl" : mac ? "clang++" : "g++";
        try {
            Process available =
                    new ProcessBuilder(windows ? "where.exe" : "which", compiler)
                            .redirectErrorStream(true)
                            .start();
            boolean found = available.waitFor(10, TimeUnit.SECONDS) && available.exitValue() == 0;
            if (Boolean.getBoolean("cppbridge.requireNativeCompiler"))
                assertTrue(found, compiler + " required");
            Assumptions.assumeTrue(found, compiler + " unavailable");
        } catch (java.io.IOException error) {
            if (Boolean.getBoolean("cppbridge.requireNativeCompiler")) throw error;
            Assumptions.abort("Compiler unavailable");
        }
        Path directory = Path.of("target/extended-native").toAbsolutePath();
        Files.createDirectories(directory);
        library =
                directory.resolve(
                        windows ? "extended.dll" : mac ? "libextended.dylib" : "libextended.so");
        Path source =
                Path.of(
                        Objects.requireNonNull(
                                        ExtendedBindingTest.class.getResource("/extended.cpp"))
                                .toURI());
        Path include =
                Path.of("../cppbridge-maven-plugin/src/main/resources/include").toAbsolutePath();
        List<String> command =
                new ArrayList<>(
                        windows
                                ? List.of(
                                        compiler,
                                        "/nologo",
                                        "/std:c++20",
                                        "/EHsc",
                                        "/utf-8",
                                        "/LD",
                                        "/I" + include)
                                : List.of(
                                        compiler,
                                        "-std=c++20",
                                        "-shared",
                                        "-fPIC",
                                        "-pthread",
                                        "-I" + include));
        command.add(source.toString());
        command.addAll(windows ? List.of("/Fe:" + library) : List.of("-o", library.toString()));
        Path log = directory.resolve("compile.log");
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Native fixture compiler timed out");
        }
        assertEquals(
                0,
                process.exitValue(),
                () -> {
                    try {
                        return Files.readString(log);
                    } catch (Exception e) {
                        return e.toString();
                    }
                });
        api = CppBridge.load(Api.class, library.toString());
    }

    @Test
    void matchesCompilerLayoutIncludingPadding() {
        StructType<Packet> type = StructType.of(Packet.class);
        assertEquals(api.packet_size(), type.byteSize());
        assertEquals(api.packet_alignment(), type.layout().byteAlignment());
        assertEquals(api.packet_origin_offset(), type.offsetOf("origin"));
        assertEquals(api.packet_samples_offset(), type.offsetOf("samples"));
        assertEquals(api.packet_enabled_offset(), type.offsetOf("enabled"));
        assertTrue(CppBridge.inspect(Api.class, library.toString()).isHealthy());
    }

    @Test
    void passesAndReturnsNestedStructsByValue() {
        assertEquals(new Point(5, 1), api.move_point(new Point(2, 4), 3));
        Packet input = new Packet((byte) 9, new Point(1, 2), new short[] {2, 4, 6}, true);
        Packet output = api.transform_packet(input);
        assertEquals(new Point(3, 2), output.origin());
        assertArrayEquals(new short[] {2, 7, 6}, output.samples());
        assertFalse(output.enabled());
        assertArrayEquals(new short[] {2, 4, 6}, input.samples());
        Scene scene =
                api.transform_scene(
                        new Scene(
                                new Point[] {new Point(1, 2), new Point(3, 4)},
                                new double[] {1, 2, 3, 4}));
        assertEquals(new Point(3, 9), scene.points()[1]);
        assertArrayEquals(new double[] {1, 2, 6, 4}, scene.matrix());
    }

    @Test
    void mutatesOwnedStructAndContiguousArray() {
        try (NativeStruct<Point> point = NativeStruct.copyOf(Point.class, new Point(1, 2));
                NativeStructArray<Point> points = NativeStructArray.allocate(Point.class, 2)) {
            api.move_pointer(point);
            assertEquals(new Point(11, 22), point.get());
            points.set(0, new Point(2, 3));
            points.set(1, new Point(4, 5));
            api.move_native_points(points);
            assertEquals(new Point(12, 23), points.get(0));
            assertEquals(new Point(14, 25), points.get(1));
            assertThrows(IndexOutOfBoundsException.class, () -> points.get(2));
        }
    }

    @Test
    void copiesRecordArraysAndPreservesAliasDirections() {
        Point[] points = {new Point(1, 2), new Point(3, 4)};
        api.move_points(points);
        assertArrayEquals(new Point[] {new Point(11, 22), new Point(13, 24)}, points);
        assertEquals(1, api.alias_points(points, points));
        assertEquals(new Point(14, 22), points[0]);
        Point[] output = new Point[2];
        api.fill_points(output);
        assertArrayEquals(new Point[] {new Point(0, 1), new Point(1, 2)}, output);
        assertDoesNotThrow(() -> api.move_points(new Point[0]));
        assertThrows(CppBridgeException.class, () -> api.move_points(new Point[] {null}));
    }

    @Test
    void handlesExplicitEnumsAndAllScalarWidths() {
        assertEquals(Color.BLUE, api.color_next(Color.RED));
        assertThrows(CppBridgeException.class, api::color_unknown);
        Color[] colors = {Color.RED};
        api.paint(colors);
        assertEquals(Color.BLUE, colors[0]);
        assertFalse(api.boolean_not(true));
        assertEquals((short) 301, api.short_inc((short) 300));
        assertEquals('\ufffe', api.char_echo('\ufffe'));
        Settings settings = api.change_settings(new Settings(Color.RED, 'a', true));
        assertEquals(new Settings(Color.BLUE, 'Ж', false), settings);
    }

    @Test
    void handlesNewHeapAndManagedArrays() {
        boolean[] flags = {true, false};
        short[] shorts = {1, 2};
        char[] chars = {'a', 'b'};
        api.invert_bools(flags);
        api.add_shorts(shorts);
        api.replace_chars(chars);
        assertArrayEquals(new boolean[] {false, true}, flags);
        assertArrayEquals(new short[] {4, 5}, shorts);
        assertArrayEquals(new char[] {'Ж', 'Ж'}, chars);
        try (NativeBooleanArray b = NativeBooleanArray.copyOf(new boolean[] {true, false});
                NativeShortArray s = NativeShortArray.copyOf(new short[] {1, 2});
                NativeCharArray c = NativeCharArray.copyOf(new char[] {'x', 'y'})) {
            api.invert_native(b);
            api.add_native(s);
            api.replace_native(c);
            assertArrayEquals(new boolean[] {false, true}, b.toArray());
            assertArrayEquals(new short[] {4, 5}, s.toArray());
            assertArrayEquals(new char[] {'Ж', 'Ж'}, c.toArray());
        }
    }

    @Test
    void copiesUtf8BeforeTemporaryMemoryCloses() {
        String text = "Привет 🌍";
        assertEquals(text, api.utf8_echo(text));
        assertEquals(
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                api.utf8_length(text));
        assertEquals("", api.utf8_echo(""));
        assertNull(api.utf8_null());
        assertThrows(CppBridgeException.class, () -> api.utf8_echo("x\0y"));
        assertThrows(CppBridgeException.class, () -> api.utf8_echo("\ud800"));
        assertThrows(CppBridgeException.class, () -> api.utf8_echo(null));
        assertThrows(CppBridgeException.class, api::utf8_bad);
        assertThrows(CppBridgeException.class, api::utf8_unterminated);
    }

    @Test
    void validatesPointerLifetimesAndBorrowsPointerFields() {
        assertEquals(MemorySegment.NULL, api.echo_pointer(MemorySegment.NULL));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = arena.allocate(ValueLayout.JAVA_INT);
            pointer.set(ValueLayout.JAVA_INT, 0, 42);
            assertEquals(pointer.address(), api.echo_pointer(pointer).address());
            Link link = api.echo_link(new Link(7, pointer));
            assertEquals(7, link.value());
            assertEquals(pointer.address(), link.next().address());
        }
        Arena arena = Arena.ofConfined();
        MemorySegment closed = arena.allocate(4);
        arena.close();
        assertThrows(CppBridgeException.class, () -> api.echo_pointer(closed));
        assertThrows(
                CppBridgeException.class,
                () -> api.echo_pointer(MemorySegment.ofArray(new int[1])));
    }

    @Test
    void invokesCallbacksOnJavaAndNativeThreads() {
        assertEquals(10, api.apply(3, value -> value * 3));
        assertEquals(14, api.apply_thread(7, value -> value * 2));
        assertEquals(
                new Point(3, 5),
                api.apply_point(new Point(2, 4), p -> new Point(p.x() + 1, p.y() + 1)));
        assertEquals(Color.BLUE, api.apply_color(Color.RED, color -> Color.BLUE));
        AtomicInteger count = new AtomicInteger();
        api.apply_void(count::incrementAndGet);
        assertEquals(1, count.get());
    }

    @Test
    void containsCallbackExceptionsWithoutLeavingNativeFrames() {
        CppBridgeException failure =
                assertThrows(
                        CppBridgeException.class,
                        () ->
                                api.apply(
                                        3,
                                        value -> {
                                            throw new IllegalStateException("callback detail");
                                        }));
        assertEquals("callback detail", failure.getCause().getMessage());
        assertThrows(
                CppBridgeException.class,
                () ->
                        api.apply_thread(
                                1,
                                value -> {
                                    throw new AssertionError("thread failure");
                                }));
        assertEquals(5, api.apply(2, value -> value * 2));
    }

    @Test
    void retainedCallbacksHaveExplicitLifetimeAndFailureChecks() {
        try (NativeCallback<IntCallback> callback =
                NativeCallback.create(
                        IntCallback.class,
                        value -> {
                            throw new IllegalArgumentException("retained");
                        })) {
            assertEquals(1, api.apply_pointer(4, callback.segment()));
            assertEquals(
                    "retained",
                    assertThrows(CppBridgeException.class, callback::checkFailure)
                            .getCause()
                            .getMessage());
            assertDoesNotThrow(callback::checkFailure);
        }
        NativeCallback<IntCallback> closed =
                NativeCallback.create(IntCallback.class, value -> value);
        closed.close();
        closed.close();
        assertThrows(CppBridgeException.class, closed::segment);
    }

    @Test
    void translatesCaughtCppExceptionsAndClearsErrors() {
        assertDoesNotThrow(() -> api.checked_operation(0));
        assertTrue(
                assertThrows(CppBridgeException.class, () -> api.checked_operation(1))
                        .getMessage()
                        .contains("bad operation"));
        assertTrue(
                assertThrows(CppBridgeException.class, () -> api.checked_operation(2))
                        .getMessage()
                        .contains("Unknown C++ exception"));
        assertDoesNotThrow(() -> api.checked_operation(0));
        assertEquals("", api.fixture_error());
    }

    @Test
    void explicitDescriptorsSupportUnionsAndVariadicFunctions() throws Throwable {
        UnionLayout number =
                MemoryLayout.unionLayout(
                        ValueLayout.JAVA_INT.withName("integer"),
                        ValueLayout.JAVA_FLOAT.withName("floating"));
        MethodHandle make =
                CppBridge.downcall(
                        library.toString(),
                        "union_make",
                        FunctionDescriptor.of(number, ValueLayout.JAVA_INT));
        MethodHandle read =
                CppBridge.downcall(
                        library.toString(),
                        "union_int",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, number));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = (MemorySegment) make.invokeExact((SegmentAllocator) arena, 123);
            assertEquals(123, (int) read.invokeExact(value));
        }
        MethodHandle sum =
                CppBridge.downcall(
                        library.toString(),
                        "variadic_sum",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_DOUBLE,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_DOUBLE,
                                ValueLayout.JAVA_DOUBLE),
                        Linker.Option.firstVariadicArg(1));
        assertEquals(7.5, (double) sum.invokeExact(2, 3.0, 4.5));
    }

    @CppStruct
    public record Point(double x, double y) {}

    @CppStruct
    public record Packet(
            byte tag, Point origin, @CppFixedArray(3) short[] samples, boolean enabled) {}

    @CppStruct
    public record Scene(@CppFixedArray(2) Point[] points, @CppFixedArray(4) double[] matrix) {}

    @CppStruct
    public record Settings(Color color, char letter, boolean active) {}

    @CppStruct
    public record Link(int value, MemorySegment next) {}

    public enum Color implements CppEnum {
        RED(7),
        BLUE(42);
        private final int value;

        Color(int value) {
            this.value = value;
        }

        public int nativeValue() {
            return value;
        }
    }

    @CppCallback
    public interface IntCallback {
        int apply(int value);
    }

    @CppCallback
    public interface PointCallback {
        Point apply(Point value);
    }

    @CppCallback
    public interface ColorCallback {
        Color apply(Color value);
    }

    @CppCallback
    public interface VoidCallback {
        void run();
    }

    @CppModule(libraryName = "extended")
    public interface Api {
        long packet_size();

        long packet_alignment();

        long packet_origin_offset();

        long packet_samples_offset();

        long packet_enabled_offset();

        Point move_point(Point point, double amount);

        Packet transform_packet(Packet packet);

        Scene transform_scene(Scene scene);

        void move_pointer(NativeStruct<Point> point);

        void move_points(Point[] points);

        @CppFunction("move_points")
        void move_native_points(NativeStructArray<Point> points);

        int alias_points(
                @CppArray(ArrayDirection.IN) Point[] input,
                @CppArray(ArrayDirection.OUT) Point[] output);

        void fill_points(@CppArray(ArrayDirection.OUT) Point[] output);

        Color color_next(Color color);

        Color color_unknown();

        void paint(Color[] colors);

        Settings change_settings(Settings settings);

        boolean boolean_not(boolean value);

        short short_inc(short value);

        char char_echo(char value);

        void invert_bools(boolean[] values);

        void add_shorts(short[] values);

        void replace_chars(char[] values);

        @CppFunction("invert_bools")
        void invert_native(NativeBooleanArray values);

        @CppFunction("add_shorts")
        void add_native(NativeShortArray values);

        @CppFunction("replace_chars")
        void replace_native(NativeCharArray values);

        int utf8_length(String value);

        @CppString(maxBytes = 1024)
        String utf8_echo(String value);

        @CppString(maxBytes = 1)
        String utf8_null();

        @CppString(maxBytes = 2)
        String utf8_bad();

        @CppString(maxBytes = 4)
        String utf8_unterminated();

        MemorySegment echo_pointer(MemorySegment value);

        Link echo_link(Link value);

        int apply(int value, IntCallback callback);

        int apply_thread(int value, IntCallback callback);

        @CppFunction("apply")
        int apply_pointer(int value, MemorySegment callback);

        Point apply_point(Point value, PointCallback callback);

        Color apply_color(Color value, ColorCallback callback);

        void apply_void(VoidCallback callback);

        @CppStatus(error = "fixture_error")
        void checked_operation(int mode);

        @CppString(maxBytes = 1024)
        String fixture_error();
    }
}
