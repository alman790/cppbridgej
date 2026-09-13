package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.memory.*;

import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;

final class NativeTypeMapper {
    private NativeTypeMapper() {}

    static boolean isPrimitiveArray(Class<?> type) {
        return type.isArray() && type.getComponentType().isPrimitive();
    }

    static boolean isHeapArray(Class<?> type) {
        return isPrimitiveArray(type)
                || (type.isArray()
                        && (NativeCodec.isStruct(type.getComponentType())
                                || NativeCodec.isEnum(type.getComponentType())));
    }

    static boolean isManagedNativeArray(Class<?> type) {
        return NativeArray.class.isAssignableFrom(type);
    }

    static boolean isArrayLike(Class<?> type) {
        return isHeapArray(type) || isManagedNativeArray(type);
    }

    static ValueLayout valueLayoutForScalar(Class<?> type) {
        var layout = NativeCodec.layout(type);
        if (layout instanceof ValueLayout value) return value;
        throw new CppBridgeException("Unsupported scalar type: " + type.getName());
    }

    static ValueLayout valueLayoutForArray(Class<?> type) {
        if (type.isArray()) return valueLayoutForScalar(type.getComponentType());
        if (type == NativeByteArray.class) return ValueLayout.JAVA_BYTE;
        if (type == NativeShortArray.class || type == NativeCharArray.class)
            return ValueLayout.JAVA_SHORT;
        if (type == NativeBooleanArray.class) return ValueLayout.JAVA_BOOLEAN;
        if (type == NativeIntArray.class) return ValueLayout.JAVA_INT;
        if (type == NativeLongArray.class) return ValueLayout.JAVA_LONG;
        if (type == NativeFloatArray.class) return ValueLayout.JAVA_FLOAT;
        if (type == NativeDoubleArray.class) return ValueLayout.JAVA_DOUBLE;
        throw new CppBridgeException("Unsupported array type: " + type.getName());
    }

    static int arrayLength(Object array) {
        if (array instanceof NativeArray nativeArray) return nativeArray.length();
        if (array != null && isHeapArray(array.getClass())) return Array.getLength(array);
        throw new CppBridgeException(
                "Unsupported array value: "
                        + (array == null ? "null" : array.getClass().getName()));
    }
}
