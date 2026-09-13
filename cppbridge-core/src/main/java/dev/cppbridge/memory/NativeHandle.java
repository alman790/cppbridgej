package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.runtime.NativeCodec;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Thread-confined ownership of an opaque C++ object with its matching destructor. */
public final class NativeHandle implements AutoCloseable {
    private final MemorySegment pointer;
    private final Consumer<MemorySegment> destructor;
    private final Thread owner = Thread.currentThread();
    private boolean closed;
    private int uses;

    private NativeHandle(MemorySegment pointer, Consumer<MemorySegment> destructor) {
        NativeCodec.checkPointer(Objects.requireNonNull(pointer, "pointer"));
        if (pointer.address() == 0) throw new CppBridgeException("Cannot own a null native handle");
        this.pointer = pointer;
        this.destructor = Objects.requireNonNull(destructor, "destructor");
    }

    public static NativeHandle own(MemorySegment pointer, Consumer<MemorySegment> destructor) {
        return new NativeHandle(pointer, destructor);
    }

    /** Runs an operation while the handle is open. The pointer must not escape this call. */
    public <R> R use(Function<MemorySegment, R> operation) {
        checkThread();
        if (closed) throw new CppBridgeException("NativeHandle is already closed");
        uses++;
        try {
            return operation.apply(pointer);
        } finally {
            uses--;
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new CppBridgeException("NativeHandle belongs to another thread");
    }

    /** Attempts destruction once. A throwing destructor is never retried. */
    @Override
    public void close() {
        checkThread();
        if (uses != 0)
            throw new CppBridgeException("Cannot close a native handle during an operation");
        if (!closed) {
            closed = true;
            destructor.accept(pointer);
        }
    }
}
