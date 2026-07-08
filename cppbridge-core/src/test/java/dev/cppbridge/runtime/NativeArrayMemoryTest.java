package dev.cppbridge.runtime;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.CppBridgeException;
import dev.cppbridge.memory.NativeDoubleArray;
import dev.cppbridge.memory.NativeFloatArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeArrayMemoryTest {
    @Test
    void allocatesInputArraysWithCopies() {
        try (Arena arena = Arena.ofConfined()) {
            int[] values = {1, 2, 3};

            MemorySegment segment = NativeArrayMemory.allocateAndCopy(arena, values, ArrayDirection.IN);
            segment.setAtIndex(ValueLayout.JAVA_INT, 1, 99);

            assertArrayEquals(new int[] {1, 2, 3}, values);
            NativeArrayMemory.copyBack(segment, values);
            assertArrayEquals(new int[] {1, 99, 3}, values);
        }
    }

    @Test
    void outputDirectionStartsZeroInitialized() {
        try (Arena arena = Arena.ofConfined()) {
            double[] values = {10.0, 20.0};

            MemorySegment segment = NativeArrayMemory.allocateAndCopy(arena, values, ArrayDirection.OUT);

            assertEquals(0.0, segment.getAtIndex(ValueLayout.JAVA_DOUBLE, 0));
            assertEquals(0.0, segment.getAtIndex(ValueLayout.JAVA_DOUBLE, 1));
        }
    }

    @Test
    void copiesBackEverySupportedHeapArrayType() {
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = new byte[1];
            MemorySegment byteSegment = arena.allocate(ValueLayout.JAVA_BYTE.byteSize(), ValueLayout.JAVA_BYTE.byteAlignment());
            byteSegment.setAtIndex(ValueLayout.JAVA_BYTE, 0, (byte) 7);
            NativeArrayMemory.copyBack(byteSegment, bytes);
            assertArrayEquals(new byte[] {7}, bytes);

            long[] longs = new long[1];
            MemorySegment longSegment = arena.allocate(ValueLayout.JAVA_LONG.byteSize(), ValueLayout.JAVA_LONG.byteAlignment());
            longSegment.setAtIndex(ValueLayout.JAVA_LONG, 0, 12L);
            NativeArrayMemory.copyBack(longSegment, longs);
            assertArrayEquals(new long[] {12L}, longs);

            float[] floats = new float[1];
            MemorySegment floatSegment = arena.allocate(ValueLayout.JAVA_FLOAT.byteSize(), ValueLayout.JAVA_FLOAT.byteAlignment());
            floatSegment.setAtIndex(ValueLayout.JAVA_FLOAT, 0, 3.5f);
            NativeArrayMemory.copyBack(floatSegment, floats);
            assertArrayEquals(new float[] {3.5f}, floats);
        }
    }

    @Test
    void exposesManagedNativeArrays() {
        try (NativeDoubleArray doubles = NativeDoubleArray.copyOf(new double[] {1.0, 2.0});
             NativeFloatArray floats = NativeFloatArray.allocate(3)) {
            assertTrue(NativeArrayMemory.isManagedNativeArray(NativeDoubleArray.class));
            assertEquals(2, NativeArrayMemory.lengthOfManagedNativeArray(doubles));
            assertEquals(3, NativeArrayMemory.lengthOfManagedNativeArray(floats));
            assertEquals(1.0, NativeArrayMemory.segmentOfManagedNativeArray(doubles).getAtIndex(ValueLayout.JAVA_DOUBLE, 0));
        }
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThrows(IllegalArgumentException.class, () -> {
            try (Arena arena = Arena.ofConfined()) {
                NativeArrayMemory.allocateAndCopy(arena, "bad", ArrayDirection.IN);
            }
        });
        assertThrows(IllegalArgumentException.class, () -> NativeArrayMemory.copyBack(MemorySegment.NULL, "bad"));
        assertThrows(CppBridgeException.class, () -> NativeArrayMemory.segmentOfManagedNativeArray("bad"));
        assertThrows(CppBridgeException.class, () -> NativeArrayMemory.lengthOfManagedNativeArray("bad"));
    }
}
