package dev.cppbridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.cppbridge.annotations.*;
import dev.cppbridge.diagnostics.BindingStatus;
import dev.cppbridge.memory.*;

import org.junit.jupiter.api.Test;

import java.lang.foreign.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class NativeValueValidationTest {
    @CppStruct
    record MissingArrayBound(int[] values) {}

    @CppStruct
    record InvalidBound(@CppFixedArray(0) byte[] values) {}

    @CppStruct
    record MisplacedBound(@CppFixedArray(2) int value) {}

    @CppStruct
    record Recursive(Recursive nested) {}

    @CppStruct
    record Empty() {}

    @CppStruct
    record Unsupported(String name) {}

    @CppStruct
    record Numbers(byte b, short s, char c, boolean active, int i, long l, float f, double d) {}

    @CppStruct
    record Fixed(@CppFixedArray(2) int[] values) {}

    @CppStruct
    static final class NotRecord {}

    enum Duplicate implements CppEnum {
        A,
        B;

        public int nativeValue() {
            return 7;
        }
    }

    @CppStruct
    record DuplicateValue(Duplicate value) {}

    @Test
    void rejectsAmbiguousAndRecursiveNativeLayouts() {
        for (Class<?> type :
                List.of(
                        MissingArrayBound.class,
                        InvalidBound.class,
                        MisplacedBound.class,
                        Recursive.class,
                        Empty.class,
                        Unsupported.class,
                        NotRecord.class,
                        DuplicateValue.class)) {
            assertThrows(CppBridgeException.class, () -> StructType.ofRecord(type), type.getName());
        }
        assertSame(StructType.of(Fixed.class), StructType.of(Fixed.class));
        try (Arena arena = Arena.ofConfined()) {
            StructType<Fixed> fixed = StructType.of(Fixed.class);
            assertThrows(
                    CppBridgeException.class,
                    () -> fixed.allocate(arena, new Fixed(new int[] {1})));
            assertThrows(CppBridgeException.class, () -> fixed.allocate(arena, new Fixed(null)));
            assertThrows(CppBridgeException.class, () -> fixed.allocate(arena, null));
        }
    }

    @Test
    void roundTripsAllFieldWidthsAndRejectsInvalidStorage() {
        StructType<Numbers> type = StructType.of(Numbers.class);
        Numbers value = new Numbers((byte) -3, (short) -5, '\uffff', true, 7, 9, 1.5f, 2.5);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = type.allocate(arena, value);
            assertEquals(value, type.read(segment));
            assertThrows(IndexOutOfBoundsException.class, () -> type.read(arena.allocate(1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> type.read(arena.allocate(type.byteSize() + 1).asSlice(1)));
            assertThrows(
                    IllegalArgumentException.class, () -> type.write(segment.asReadOnly(), value));
        }
    }

    @Test
    void ownedStructsKeepWorkingAfterRejectedForeignThreadClose() throws Exception {
        NativeStruct<Fixed> value = NativeStruct.copyOf(Fixed.class, new Fixed(new int[] {1, 2}));
        NativeStructArray<Fixed> array = NativeStructArray.allocate(Fixed.class, 2);
        try (value;
                array;
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertThrows(ExecutionException.class, () -> executor.submit(value::close).get());
            assertThrows(ExecutionException.class, () -> executor.submit(array::close).get());
            assertArrayEquals(new int[] {1, 2}, value.get().values());
            array.set(1, new Fixed(new int[] {3, 4}));
            assertArrayEquals(new int[] {3, 4}, array.get(1).values());
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> array.get(2));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NativeStructArray.allocate(Fixed.class, -1));
        }
        assertDoesNotThrow(value::close);
        assertDoesNotThrow(array::close);
        assertThrows(CppBridgeException.class, value::get);
        assertThrows(CppBridgeException.class, array::length);
        assertThrows(
                CppBridgeException.class, () -> NativeStruct.copyOf(Fixed.class, new Fixed(null)));
    }

    @Test
    void handlesPreventUseAfterCloseAndRepeatedDestruction() throws Exception {
        AtomicInteger deleted = new AtomicInteger();
        try (Arena arena = Arena.ofConfined()) {
            NativeHandle handle =
                    NativeHandle.own(arena.allocate(4), pointer -> deleted.incrementAndGet());
            assertThrows(
                    CppBridgeException.class,
                    () ->
                            handle.use(
                                    pointer -> {
                                        handle.close();
                                        return null;
                                    }));
            assertEquals(0, deleted.get());
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                assertThrows(ExecutionException.class, () -> executor.submit(handle::close).get());
            }
            assertNotEquals(0L, handle.use(MemorySegment::address));
            handle.close();
            handle.close();
            assertEquals(1, deleted.get());
            assertThrows(CppBridgeException.class, () -> handle.use(MemorySegment::address));
            NativeHandle failing =
                    NativeHandle.own(
                            arena.allocate(4),
                            pointer -> {
                                deleted.incrementAndGet();
                                throw new IllegalStateException();
                            });
            assertThrows(IllegalStateException.class, failing::close);
            failing.close();
            assertEquals(2, deleted.get());
        }
        assertThrows(
                CppBridgeException.class,
                () -> NativeHandle.own(MemorySegment.NULL, pointer -> {}));
    }

    @CppCallback
    interface InvalidCallback {
        int first();

        int second();
    }

    @CppCallback
    interface GenericCallback<T> {
        T call(T value);
    }

    @CppCallback
    interface BadArgumentCallback {
        int call(String argument);
    }

    @Test
    void rejectsInvalidCallbacksBeforeCreatingNativeStubs() {
        assertThrows(
                CppBridgeException.class, () -> NativeCallback.descriptor(InvalidCallback.class));
        assertThrows(
                CppBridgeException.class, () -> NativeCallback.descriptor(GenericCallback.class));
        assertThrows(
                CppBridgeException.class,
                () -> NativeCallback.descriptor(BadArgumentCallback.class));
        assertThrows(CppBridgeException.class, () -> NativeCallback.descriptor(Runnable.class));
    }

    @CppModule(libraryName = "absent")
    interface InvalidSignatures {
        String missingStringBound();

        @CppString(maxBytes = 0)
        String zeroBound();

        @CppString(maxBytes = 10)
        int wrongStringReturn();

        int wrongParameter(@CppString(maxBytes = 10) int value);

        @CppStatus(error = "error")
        int wrongStatusReturn();

        @CppStatus(error = "")
        void emptyErrorSymbol();

        void rawStruct(NativeStruct value);

        void wildcardStruct(NativeStruct<?> value);

        void invalidCallback(InvalidCallback callback);

        void structWithString(Unsupported value);
    }

    @Test
    void inspectionAndLoadRejectUnsupportedSignaturesConsistently() {
        var report =
                CppBridge.inspect(
                        InvalidSignatures.class, Path.of("target/absent-library").toString());
        assertEquals(10, report.entries().size());
        assertTrue(
                report.entries().stream()
                        .allMatch(entry -> entry.status() == BindingStatus.UNSUPPORTED_SIGNATURE));
        assertThrows(
                CppBridgeException.class,
                () -> CppBridge.load(InvalidSignatures.class, "target/absent-library"));
    }

    @Test
    void newPrimitiveBuffersValidateBoundsSizesAndLifetime() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> NativeShortArray.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> NativeCharArray.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> NativeBooleanArray.allocate(-1));
        NativeShortArray shorts = NativeShortArray.allocate(2);
        NativeCharArray chars = NativeCharArray.allocate(2);
        NativeBooleanArray flags = NativeBooleanArray.allocate(2);
        try (shorts;
                chars;
                flags;
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            shorts.set(0, (short) 9);
            chars.set(0, 'я');
            flags.set(0, true);
            assertEquals((short) 9, shorts.get(0));
            assertEquals('я', chars.get(0));
            assertTrue(flags.get(0));
            assertEquals(2, shorts.length());
            assertEquals(2, chars.length());
            assertEquals(2, flags.length());
            assertThrows(IndexOutOfBoundsException.class, () -> shorts.set(2, (short) 1));
            assertThrows(IndexOutOfBoundsException.class, () -> chars.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> flags.get(2));
            assertThrows(CppBridgeException.class, () -> shorts.copyFrom(new short[1]));
            assertThrows(CppBridgeException.class, () -> shorts.copyTo(new short[3]));
            assertThrows(CppBridgeException.class, () -> chars.copyFrom(new char[1]));
            assertThrows(CppBridgeException.class, () -> chars.copyTo(new char[3]));
            assertThrows(CppBridgeException.class, () -> flags.copyFrom(new boolean[1]));
            assertThrows(CppBridgeException.class, () -> flags.copyTo(new boolean[3]));
            for (NativeArray array : List.of(shorts, chars, flags)) {
                assertThrows(ExecutionException.class, () -> executor.submit(array::close).get());
                assertEquals(2, array.length());
            }
        }
        for (NativeArray array : List.of(shorts, chars, flags)) {
            array.close();
            assertThrows(CppBridgeException.class, array::segment);
        }
    }
}
