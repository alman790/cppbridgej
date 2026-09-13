package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppFixedArray;
import dev.cppbridge.annotations.CppStruct;
import dev.cppbridge.runtime.NativeCodec;

import java.lang.foreign.*;
import java.lang.reflect.*;
import java.util.*;

/** Native size, alignment, offsets and record conversion for a C-compatible struct. */
public final class StructType<T extends Record> {
    private static final ThreadLocal<Set<Class<?>>> BUILDING =
            ThreadLocal.withInitial(HashSet::new);
    private static final ClassValue<StructType<?>> TYPES =
            new ClassValue<>() {
                @Override
                protected StructType<?> computeValue(Class<?> type) {
                    return new StructType<>(type);
                }
            };
    private final Class<?> type;
    private final StructLayout layout;
    private final Constructor<?> constructor;
    private final List<Field> fields;

    private StructType(Class<?> type) {
        this.type = type;
        if (!type.isRecord() || !type.isAnnotationPresent(CppStruct.class)) {
            throw new CppBridgeException("@CppStruct requires a record: " + type.getName());
        }
        Set<Class<?>> building = BUILDING.get();
        if (!building.add(type))
            throw new CppBridgeException("Recursive inline struct: " + type.getName());
        try {
            RecordComponent[] components = type.getRecordComponents();
            if (components.length == 0)
                throw new CppBridgeException(
                        "Empty C++ structs are not supported: " + type.getName());
            List<MemoryLayout> members = new ArrayList<>();
            List<Field> collected = new ArrayList<>();
            long offset = 0, alignment = 1;
            for (RecordComponent component : components) {
                Class<?> componentType = component.getType();
                CppFixedArray array = component.getAnnotation(CppFixedArray.class);
                MemoryLayout member;
                int count = 0;
                if (componentType.isArray()) {
                    if (array == null || array.value() <= 0)
                        throw new CppBridgeException(
                                "Inline array needs positive @CppFixedArray: "
                                        + component.getName());
                    count = array.value();
                    member =
                            MemoryLayout.sequenceLayout(
                                    count, NativeCodec.layout(componentType.getComponentType()));
                } else {
                    if (array != null)
                        throw new CppBridgeException(
                                "@CppFixedArray requires an array: " + component.getName());
                    member = NativeCodec.layout(componentType);
                }
                long padding = Math.floorMod(-offset, member.byteAlignment());
                if (padding > 0) members.add(MemoryLayout.paddingLayout(padding));
                offset = Math.addExact(offset, padding);
                Method accessor = component.getAccessor();
                if (!accessor.trySetAccessible())
                    throw new CppBridgeException(
                            "Open record package to CppBridgeJ: " + type.getName());
                collected.add(
                        new Field(
                                component.getName(),
                                componentType,
                                accessor,
                                offset,
                                member.byteSize(),
                                count));
                members.add(member.withName(component.getName()));
                offset = Math.addExact(offset, member.byteSize());
                alignment = Math.max(alignment, member.byteAlignment());
            }
            long padding = Math.floorMod(-offset, alignment);
            if (padding > 0) members.add(MemoryLayout.paddingLayout(padding));
            layout = MemoryLayout.structLayout(members.toArray(MemoryLayout[]::new));
            fields = List.copyOf(collected);
            constructor =
                    type.getDeclaredConstructor(
                            Arrays.stream(components)
                                    .map(RecordComponent::getType)
                                    .toArray(Class<?>[]::new));
            if (!constructor.trySetAccessible())
                throw new CppBridgeException(
                        "Open record package to CppBridgeJ: " + type.getName());
        } catch (ReflectiveOperationException error) {
            throw new CppBridgeException("Cannot access record " + type.getName(), error);
        } finally {
            building.remove(type);
            if (building.isEmpty()) BUILDING.remove();
        }
    }

    /** Returns a cached schema. Boxed scalar fields have the same representation as primitives. */
    @SuppressWarnings("unchecked")
    public static <T extends Record> StructType<T> of(Class<T> type) {
        return (StructType<T>) TYPES.get(Objects.requireNonNull(type));
    }

    /** Resolves a record type discovered through reflection. */
    public static StructType<?> ofRecord(Class<?> type) {
        return TYPES.get(type);
    }

    public StructLayout layout() {
        return layout;
    }

    public long byteSize() {
        return layout.byteSize();
    }

    public long offsetOf(String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    /** Allocates a value in an arena owned by the caller. Pointer fields remain borrowed. */
    public MemorySegment allocate(Arena arena, T value) {
        return allocateValue(arena, value);
    }

    public MemorySegment allocateValue(Arena arena, Object value) {
        MemorySegment segment = arena.allocate(layout);
        writeValue(segment, value);
        return segment;
    }

    /** Copies a record into an existing, suitably aligned segment. */
    public void write(MemorySegment segment, T value) {
        writeValue(segment, value);
    }

    public void writeValue(MemorySegment segment, Object value) {
        if (!type.isInstance(value))
            throw new CppBridgeException("Expected non-null " + type.getName());
        MemorySegment target = segment.asSlice(0, layout);
        try {
            for (Field field : fields) {
                Object member = field.accessor().invoke(value);
                MemorySegment slice = target.asSlice(field.offset(), field.size());
                if (field.count() == 0) NativeCodec.write(field.type(), slice, member);
                else {
                    if (member == null || Array.getLength(member) != field.count()) {
                        throw new CppBridgeException(
                                "Inline array "
                                        + field.name()
                                        + " requires "
                                        + field.count()
                                        + " elements");
                    }
                    long size = field.size() / field.count();
                    for (int i = 0; i < field.count(); i++)
                        NativeCodec.write(
                                field.type().getComponentType(),
                                slice.asSlice(i * size, size),
                                Array.get(member, i));
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new CppBridgeException("Cannot read record " + type.getName(), error);
        }
    }

    /**
     * Copies native fields into a record; inline arrays are copied, pointer fields are borrowed.
     */
    @SuppressWarnings("unchecked")
    public T read(MemorySegment segment) {
        MemorySegment source = segment.asSlice(0, layout);
        Object[] values = new Object[fields.size()];
        for (int j = 0; j < fields.size(); j++) {
            Field field = fields.get(j);
            MemorySegment slice = source.asSlice(field.offset(), field.size());
            if (field.count() == 0) values[j] = NativeCodec.read(field.type(), slice);
            else {
                Object array = Array.newInstance(field.type().getComponentType(), field.count());
                long size = field.size() / field.count();
                for (int i = 0; i < field.count(); i++)
                    Array.set(
                            array,
                            i,
                            NativeCodec.read(
                                    field.type().getComponentType(),
                                    slice.asSlice(i * size, size)));
                values[j] = array;
            }
        }
        try {
            return (T) constructor.newInstance(values);
        } catch (ReflectiveOperationException error) {
            throw new CppBridgeException("Cannot construct native record " + type.getName(), error);
        }
    }

    private record Field(
            String name, Class<?> type, Method accessor, long offset, long size, int count) {}
}
