package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** An owned, thread-confined struct passed to native code by pointer. */
public final class NativeStruct<T extends Record> implements AutoCloseable {
    private final Arena arena;
    private final StructType<T> type;
    private final MemorySegment segment;
    private boolean closed;

    private NativeStruct(StructType<T> type, Arena arena) {
        this.type = type;
        this.arena = arena;
        segment = arena.allocate(type.layout());
    }

    public static <T extends Record> NativeStruct<T> allocate(Class<T> recordType) {
        StructType<T> type = StructType.of(recordType);
        Arena arena = Arena.ofConfined();
        try {
            return new NativeStruct<>(type, arena);
        } catch (RuntimeException | Error error) {
            arena.close();
            throw error;
        }
    }

    public static <T extends Record> NativeStruct<T> copyOf(Class<T> recordType, T value) {
        NativeStruct<T> result = allocate(recordType);
        try {
            result.set(value);
            return result;
        } catch (RuntimeException | Error error) {
            result.close();
            throw error;
        }
    }

    public T get() {
        return type.read(segment());
    }

    public void set(T value) {
        type.write(segment(), value);
    }

    public StructType<T> type() {
        return type;
    }

    public MemorySegment segment() {
        if (closed) throw new CppBridgeException("NativeStruct is already closed");
        return segment;
    }

    @Override
    public void close() {
        if (!closed) {
            arena.close();
            closed = true;
        }
    }
}
