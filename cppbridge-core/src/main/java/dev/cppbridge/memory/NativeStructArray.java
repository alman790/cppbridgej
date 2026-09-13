package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/** Owned contiguous structs. An API parameter expands to a pointer and int32_t length. */
public final class NativeStructArray<T extends Record> implements NativeArray {
    private final Arena arena;
    private final StructType<T> type;
    private final MemorySegment segment;
    private final int length;
    private boolean closed;

    private NativeStructArray(StructType<T> type, Arena arena, int length) {
        this.type = type;
        this.arena = arena;
        this.length = length;
        segment =
                arena.allocate(
                        Math.multiplyExact(type.byteSize(), length), type.layout().byteAlignment());
    }

    public static <T extends Record> NativeStructArray<T> allocate(
            Class<T> recordType, int length) {
        if (length < 0) throw new IllegalArgumentException("length must be >= 0");
        StructType<T> type = StructType.of(recordType);
        Arena arena = Arena.ofConfined();
        try {
            return new NativeStructArray<>(type, arena, length);
        } catch (RuntimeException | Error error) {
            arena.close();
            throw error;
        }
    }

    public int length() {
        segment();
        return length;
    }

    public StructType<T> type() {
        return type;
    }

    public MemorySegment segment() {
        if (closed) throw new CppBridgeException("NativeStructArray is already closed");
        return segment;
    }

    private MemorySegment element(int index) {
        Objects.checkIndex(index, length);
        return segment().asSlice(index * type.byteSize(), type.layout());
    }

    public T get(int index) {
        return type.read(element(index));
    }

    public void set(int index, T value) {
        type.write(element(index), value);
    }

    @Override
    public void close() {
        if (!closed) {
            arena.close();
            closed = true;
        }
    }
}
