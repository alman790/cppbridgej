package dev.cppbridge.runtime;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.CppBridgeException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class NativeArrayMemory {
    private NativeArrayMemory() {}

    static boolean isManagedNativeArray(Class<?> type) {
        return dev.cppbridge.memory.NativeArray.class.isAssignableFrom(type);
    }

    static MemorySegment segmentOfManagedNativeArray(Object value) {
        if (value instanceof dev.cppbridge.memory.NativeArray array) return array.segment();
        throw new CppBridgeException(
                "Unsupported native array value: " + value.getClass().getName());
    }

    static int lengthOfManagedNativeArray(Object value) {
        if (value instanceof dev.cppbridge.memory.NativeArray array) return array.length();
        throw new CppBridgeException(
                "Unsupported native array value: " + value.getClass().getName());
    }

    static MemorySegment allocateAndCopy(Arena arena, Object array, ArrayDirection direction) {
        if (array instanceof byte[] values) {
            MemorySegment segment =
                    arena.allocate(
                            ValueLayout.JAVA_BYTE.byteSize() * values.length,
                            ValueLayout.JAVA_BYTE.byteAlignment());
            if (direction != ArrayDirection.OUT) {
                segment.copyFrom(MemorySegment.ofArray(values));
            }
            return segment;
        }

        if (array instanceof int[] values) {
            MemorySegment segment =
                    arena.allocate(
                            ValueLayout.JAVA_INT.byteSize() * values.length,
                            ValueLayout.JAVA_INT.byteAlignment());
            if (direction != ArrayDirection.OUT) {
                segment.copyFrom(MemorySegment.ofArray(values));
            }
            return segment;
        }

        if (array instanceof long[] values) {
            MemorySegment segment =
                    arena.allocate(
                            ValueLayout.JAVA_LONG.byteSize() * values.length,
                            ValueLayout.JAVA_LONG.byteAlignment());
            if (direction != ArrayDirection.OUT) {
                segment.copyFrom(MemorySegment.ofArray(values));
            }
            return segment;
        }

        if (array instanceof float[] values) {
            MemorySegment segment =
                    arena.allocate(
                            ValueLayout.JAVA_FLOAT.byteSize() * values.length,
                            ValueLayout.JAVA_FLOAT.byteAlignment());
            if (direction != ArrayDirection.OUT) {
                segment.copyFrom(MemorySegment.ofArray(values));
            }
            return segment;
        }

        if (array instanceof double[] values) {
            MemorySegment segment =
                    arena.allocate(
                            ValueLayout.JAVA_DOUBLE.byteSize() * values.length,
                            ValueLayout.JAVA_DOUBLE.byteAlignment());
            if (direction != ArrayDirection.OUT) {
                segment.copyFrom(MemorySegment.ofArray(values));
            }
            return segment;
        }

        if (NativeTypeMapper.isHeapArray(array.getClass())) {
            Class<?> component = array.getClass().getComponentType();
            var layout = NativeCodec.layout(component);
            int length = java.lang.reflect.Array.getLength(array);
            MemorySegment memory =
                    arena.allocate(
                            Math.multiplyExact(layout.byteSize(), length), layout.byteAlignment());
            if (direction != ArrayDirection.OUT) {
                for (int i = 0; i < length; i++)
                    NativeCodec.write(
                            component,
                            memory.asSlice(i * layout.byteSize(), layout.byteSize()),
                            java.lang.reflect.Array.get(array, i));
            }
            return memory;
        }
        throw new IllegalArgumentException("Unsupported array type: " + array.getClass().getName());
    }

    static void copyBack(MemorySegment segment, Object array) {
        if (array instanceof byte[] values) {
            MemorySegment.ofArray(values).copyFrom(segment);
            return;
        }

        if (array instanceof int[] values) {
            MemorySegment.ofArray(values).copyFrom(segment);
            return;
        }

        if (array instanceof long[] values) {
            MemorySegment.ofArray(values).copyFrom(segment);
            return;
        }

        if (array instanceof float[] values) {
            MemorySegment.ofArray(values).copyFrom(segment);
            return;
        }

        if (array instanceof double[] values) {
            MemorySegment.ofArray(values).copyFrom(segment);
            return;
        }

        if (NativeTypeMapper.isHeapArray(array.getClass())) {
            Class<?> component = array.getClass().getComponentType();
            long size = NativeCodec.layout(component).byteSize();
            int length = java.lang.reflect.Array.getLength(array);
            for (int i = 0; i < length; i++)
                java.lang.reflect.Array.set(
                        array, i, NativeCodec.read(component, segment.asSlice(i * size, size)));
            return;
        }
        throw new IllegalArgumentException("Unsupported array type: " + array.getClass().getName());
    }
}
