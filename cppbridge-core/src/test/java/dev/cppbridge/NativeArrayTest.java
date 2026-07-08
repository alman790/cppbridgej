package dev.cppbridge;

import dev.cppbridge.memory.NativeByteArray;
import dev.cppbridge.memory.NativeDoubleArray;
import dev.cppbridge.memory.NativeFloatArray;
import dev.cppbridge.memory.NativeIntArray;
import dev.cppbridge.memory.NativeLongArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeArrayTest {
    @Test
    void nativeDoubleArrayCopiesValuesBothWays() {
        try (NativeDoubleArray values = NativeDoubleArray.copyOf(new double[] {1.0, 2.0, 3.0})) {
            assertEquals(3, values.length());
            assertEquals(2.0, values.get(1));
            values.set(1, 42.0);
            assertArrayEquals(new double[] {1.0, 42.0, 3.0}, values.toArray());
        }
    }

    @Test
    void nativeByteArraySupportsUnsignedAccess() {
        try (NativeByteArray values = NativeByteArray.copyOf(new byte[] {(byte) 10, (byte) 255})) {
            assertEquals(255, values.getUnsigned(1));
            values.setUnsigned(0, 250);
            assertEquals(250, values.getUnsigned(0));
            assertThrows(IllegalArgumentException.class, () -> values.setUnsigned(0, 300));
        }
    }

    @Test
    void nativeIntArrayRejectsWrongCopyLength() {
        try (NativeIntArray values = NativeIntArray.allocate(3)) {
            assertThrows(CppBridgeException.class, () -> values.copyFrom(new int[] {1, 2}));
        }
    }

    @Test
    void nativeLongArrayRejectsUseAfterClose() {
        NativeLongArray values = NativeLongArray.copyOf(new long[] {1, 2, 3});
        values.close();
        assertThrows(CppBridgeException.class, values::length);
    }

    @Test
    void nativeArraysRejectNegativeLengthsAndBadIndexes() {
        assertThrows(IllegalArgumentException.class, () -> NativeByteArray.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> NativeDoubleArray.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> NativeFloatArray.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> NativeIntArray.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> NativeLongArray.allocate(-1));

        try (NativeFloatArray values = NativeFloatArray.copyOf(new float[] {1.0f, 2.0f})) {
            assertEquals(2, values.length());
            assertEquals(1.0f, values.get(0));
            values.set(1, 4.0f);
            assertArrayEquals(new float[] {1.0f, 4.0f}, values.toArray());
            assertThrows(IndexOutOfBoundsException.class, () -> values.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> values.set(2, 1.0f));
        }
    }

    @Test
    void nativeArraysRejectWrongTargetLengthsAndNullCopies() {
        try (NativeByteArray bytes = NativeByteArray.allocate(1);
             NativeDoubleArray doubles = NativeDoubleArray.allocate(1);
             NativeFloatArray floats = NativeFloatArray.allocate(1);
             NativeIntArray ints = NativeIntArray.allocate(1);
             NativeLongArray longs = NativeLongArray.allocate(1)) {
            assertThrows(NullPointerException.class, () -> bytes.copyFrom(null));
            assertThrows(CppBridgeException.class, () -> bytes.copyTo(new byte[2]));
            assertThrows(CppBridgeException.class, () -> doubles.copyTo(new double[2]));
            assertThrows(CppBridgeException.class, () -> floats.copyTo(new float[2]));
            assertThrows(CppBridgeException.class, () -> ints.copyTo(new int[2]));
            assertThrows(CppBridgeException.class, () -> longs.copyTo(new long[2]));
        }
    }
}
