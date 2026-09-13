package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.CppEnum;
import dev.cppbridge.annotations.CppStruct;
import dev.cppbridge.memory.StructType;

import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Shared ABI conversion used by bindings and native record storage. */
public final class NativeCodec {
    private NativeCodec() {}

    private static final ClassValue<Map<Integer, Object>> ENUMS =
            new ClassValue<>() {
                @Override
                protected Map<Integer, Object> computeValue(Class<?> type) {
                    Map<Integer, Object> values = new HashMap<>();
                    for (Object value : type.getEnumConstants()) {
                        int number = ((CppEnum) value).nativeValue();
                        if (values.put(number, value) != null) {
                            throw new CppBridgeException(
                                    "Duplicate native enum value "
                                            + number
                                            + " in "
                                            + type.getName());
                        }
                    }
                    return Map.copyOf(values);
                }
            };

    public static boolean isStruct(Class<?> type) {
        return type.isAnnotationPresent(CppStruct.class);
    }

    public static boolean isEnum(Class<?> type) {
        return type.isEnum() && CppEnum.class.isAssignableFrom(type);
    }

    public static MemoryLayout layout(Class<?> type) {
        if (isStruct(type)) return StructType.ofRecord(type).layout();
        if (isEnum(type)) {
            ENUMS.get(type);
            return ValueLayout.JAVA_INT;
        }
        if (type == byte.class || type == Byte.class) return ValueLayout.JAVA_BYTE;
        if (type == short.class
                || type == Short.class
                || type == char.class
                || type == Character.class) return ValueLayout.JAVA_SHORT;
        if (type == boolean.class || type == Boolean.class) return ValueLayout.JAVA_BOOLEAN;
        if (type == int.class || type == Integer.class) return ValueLayout.JAVA_INT;
        if (type == long.class || type == Long.class) return ValueLayout.JAVA_LONG;
        if (type == float.class || type == Float.class) return ValueLayout.JAVA_FLOAT;
        if (type == double.class || type == Double.class) return ValueLayout.JAVA_DOUBLE;
        if (type == MemorySegment.class) return ValueLayout.ADDRESS;
        throw new CppBridgeException("Unsupported native value type: " + type.getName());
    }

    public static Object toNative(Class<?> type, Object value, Arena arena) {
        if (value == null)
            throw new CppBridgeException("Native value cannot be null: " + type.getName());
        if (isStruct(type)) return StructType.ofRecord(type).allocateValue(arena, value);
        if (isEnum(type)) return ((CppEnum) value).nativeValue();
        if (type == char.class || type == Character.class)
            return (short) ((Character) value).charValue();
        if (type == MemorySegment.class) checkPointer((MemorySegment) value);
        return value;
    }

    public static Object fromNative(Class<?> type, Object value) {
        if (type == void.class || type == Void.class) return null;
        if (isStruct(type)) return StructType.ofRecord(type).read((MemorySegment) value);
        if (isEnum(type)) {
            Object result = ENUMS.get(type).get((Integer) value);
            if (result == null)
                throw new CppBridgeException(
                        "Unknown native enum value " + value + " for " + type.getName());
            return result;
        }
        if (type == char.class || type == Character.class)
            return (char) Short.toUnsignedInt((Short) value);
        return value;
    }

    public static void checkPointer(MemorySegment value) {
        if (!value.isNative())
            throw new CppBridgeException("Native pointers require off-heap memory");
        if (!value.scope().isAlive())
            throw new CppBridgeException("Native pointer scope is closed");
        if (!value.isAccessibleBy(Thread.currentThread()))
            throw new CppBridgeException("Native pointer belongs to another thread");
    }

    public static Object read(Class<?> type, MemorySegment memory) {
        if (isStruct(type)) return StructType.ofRecord(type).read(memory);
        ValueLayout layout = (ValueLayout) layout(type);
        Object value;
        if (layout instanceof ValueLayout.OfByte l) value = memory.get(l, 0);
        else if (layout instanceof ValueLayout.OfShort l) value = memory.get(l, 0);
        else if (layout instanceof ValueLayout.OfBoolean l) value = memory.get(l, 0);
        else if (layout instanceof ValueLayout.OfInt l) value = memory.get(l, 0);
        else if (layout instanceof ValueLayout.OfLong l) value = memory.get(l, 0);
        else if (layout instanceof ValueLayout.OfFloat l) value = memory.get(l, 0);
        else if (layout instanceof ValueLayout.OfDouble l) value = memory.get(l, 0);
        else value = memory.get((AddressLayout) layout, 0);
        return fromNative(type, value);
    }

    public static void write(Class<?> type, MemorySegment memory, Object value) {
        if (isStruct(type)) {
            StructType.ofRecord(type).writeValue(memory, value);
            return;
        }
        Object nativeValue = toNative(type, value, null);
        ValueLayout layout = (ValueLayout) layout(type);
        if (layout instanceof ValueLayout.OfByte l) memory.set(l, 0, (Byte) nativeValue);
        else if (layout instanceof ValueLayout.OfShort l) memory.set(l, 0, (Short) nativeValue);
        else if (layout instanceof ValueLayout.OfBoolean l) memory.set(l, 0, (Boolean) nativeValue);
        else if (layout instanceof ValueLayout.OfInt l) memory.set(l, 0, (Integer) nativeValue);
        else if (layout instanceof ValueLayout.OfLong l) memory.set(l, 0, (Long) nativeValue);
        else if (layout instanceof ValueLayout.OfFloat l) memory.set(l, 0, (Float) nativeValue);
        else if (layout instanceof ValueLayout.OfDouble l) memory.set(l, 0, (Double) nativeValue);
        else memory.set((AddressLayout) layout, 0, (MemorySegment) nativeValue);
    }

    public static MemorySegment string(Arena arena, String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new CppBridgeException("UTF-8 string must be non-null and must not contain NUL");
        }
        try {
            ByteBuffer bytes =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(value));
            MemorySegment segment = arena.allocate(bytes.remaining() + 1L);
            segment.asSlice(0, bytes.remaining()).copyFrom(MemorySegment.ofBuffer(bytes));
            return segment;
        } catch (CharacterCodingException error) {
            throw new CppBridgeException("String contains an unpaired UTF-16 surrogate", error);
        }
    }

    public static String string(MemorySegment pointer, int maxBytes) {
        if (maxBytes <= 0) throw new CppBridgeException("String bound must be positive");
        if (pointer.address() == 0) return null;
        MemorySegment bytes = pointer.reinterpret(maxBytes);
        int length = 0;
        while (length < maxBytes && bytes.get(ValueLayout.JAVA_BYTE, length) != 0) length++;
        if (length == maxBytes)
            throw new CppBridgeException(
                    "Native string is not NUL-terminated within " + maxBytes + " bytes");
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(bytes.asSlice(0, length).asByteBuffer())
                    .toString();
        } catch (CharacterCodingException error) {
            throw new CppBridgeException("Native string is not valid UTF-8", error);
        }
    }
}
