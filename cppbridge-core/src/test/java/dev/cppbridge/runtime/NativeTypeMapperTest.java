package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.memory.NativeByteArray;
import dev.cppbridge.memory.NativeDoubleArray;
import dev.cppbridge.memory.NativeFloatArray;
import dev.cppbridge.memory.NativeIntArray;
import dev.cppbridge.memory.NativeLongArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTypeMapperTest {
    @Test
    void recognizesSupportedArrayTypes() {
        assertTrue(NativeTypeMapper.isPrimitiveArray(byte[].class));
        assertTrue(NativeTypeMapper.isPrimitiveArray(int[].class));
        assertTrue(NativeTypeMapper.isPrimitiveArray(long[].class));
        assertTrue(NativeTypeMapper.isPrimitiveArray(float[].class));
        assertTrue(NativeTypeMapper.isPrimitiveArray(double[].class));
        assertFalse(NativeTypeMapper.isPrimitiveArray(short[].class));

        assertTrue(NativeTypeMapper.isManagedNativeArray(NativeByteArray.class));
        assertTrue(NativeTypeMapper.isManagedNativeArray(NativeIntArray.class));
        assertTrue(NativeTypeMapper.isManagedNativeArray(NativeLongArray.class));
        assertTrue(NativeTypeMapper.isManagedNativeArray(NativeFloatArray.class));
        assertTrue(NativeTypeMapper.isManagedNativeArray(NativeDoubleArray.class));
        assertFalse(NativeTypeMapper.isManagedNativeArray(String.class));
    }

    @Test
    void mapsScalarLayouts() {
        assertEquals(ValueLayout.JAVA_BYTE, NativeTypeMapper.valueLayoutForScalar(Byte.class));
        assertEquals(ValueLayout.JAVA_INT, NativeTypeMapper.valueLayoutForScalar(Integer.class));
        assertEquals(ValueLayout.JAVA_LONG, NativeTypeMapper.valueLayoutForScalar(Long.class));
        assertEquals(ValueLayout.JAVA_FLOAT, NativeTypeMapper.valueLayoutForScalar(Float.class));
        assertEquals(ValueLayout.JAVA_DOUBLE, NativeTypeMapper.valueLayoutForScalar(Double.class));
    }

    @Test
    void mapsArrayLayouts() {
        assertEquals(ValueLayout.JAVA_BYTE, NativeTypeMapper.valueLayoutForArray(byte[].class));
        assertEquals(ValueLayout.JAVA_INT, NativeTypeMapper.valueLayoutForArray(NativeIntArray.class));
        assertEquals(ValueLayout.JAVA_LONG, NativeTypeMapper.valueLayoutForArray(long[].class));
        assertEquals(ValueLayout.JAVA_FLOAT, NativeTypeMapper.valueLayoutForArray(NativeFloatArray.class));
        assertEquals(ValueLayout.JAVA_DOUBLE, NativeTypeMapper.valueLayoutForArray(double[].class));
    }

    @Test
    void reportsUnsupportedMappings() {
        assertThrows(CppBridgeException.class, () -> NativeTypeMapper.valueLayoutForScalar(boolean.class));
        assertThrows(CppBridgeException.class, () -> NativeTypeMapper.valueLayoutForArray(short[].class));
        assertThrows(CppBridgeException.class, () -> NativeTypeMapper.arrayLength("not an array"));
    }

    @Test
    void returnsManagedAndHeapArrayLengths() {
        assertEquals(2, NativeTypeMapper.arrayLength(new byte[] {1, 2}));
        assertEquals(3, NativeTypeMapper.arrayLength(new int[] {1, 2, 3}));
        assertEquals(1, NativeTypeMapper.arrayLength(new long[] {1L}));
        assertEquals(2, NativeTypeMapper.arrayLength(new float[] {1.0f, 2.0f}));
        assertEquals(1, NativeTypeMapper.arrayLength(new double[] {1.0}));

        try (NativeByteArray bytes = NativeByteArray.allocate(4);
             NativeIntArray ints = NativeIntArray.allocate(5);
             NativeLongArray longs = NativeLongArray.allocate(6);
             NativeFloatArray floats = NativeFloatArray.allocate(7);
             NativeDoubleArray doubles = NativeDoubleArray.allocate(8)) {
            assertEquals(4, NativeTypeMapper.arrayLength(bytes));
            assertEquals(5, NativeTypeMapper.arrayLength(ints));
            assertEquals(6, NativeTypeMapper.arrayLength(longs));
            assertEquals(7, NativeTypeMapper.arrayLength(floats));
            assertEquals(8, NativeTypeMapper.arrayLength(doubles));
        }
    }
}
