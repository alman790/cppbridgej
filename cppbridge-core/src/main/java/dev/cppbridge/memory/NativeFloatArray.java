package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Off-heap {@code float[]} wrapper for single-precision numeric kernels.
 *
 * <p>The array owns a confined {@link Arena}. Native memory is released when
 * {@link #close()} is called. Use try-with-resources for deterministic cleanup.</p>
 *
 * <p>This type is intended for hot paths where the same data is passed to C++
 * repeatedly. It avoids copying a Java heap array into native memory for every
 * native call.</p>
 */
public final class NativeFloatArray implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment segment;
    private final int length;
    private boolean closed;

    private NativeFloatArray(Arena arena, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        this.arena = Objects.requireNonNull(arena, "arena");
        this.length = length;
        this.segment = arena.allocate(ValueLayout.JAVA_FLOAT.byteSize() * length, ValueLayout.JAVA_FLOAT.byteAlignment());
    }

    /**
     * Allocates a zero-initialized native array.
     *
     * @param length number of elements
     * @return allocated native array
     */
    public static NativeFloatArray allocate(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        Arena arena = Arena.ofConfined();
        try {
            return new NativeFloatArray(arena, length);
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    /**
     * Allocates native memory and copies values from a Java heap array.
     *
     * @param values source values
     * @return allocated native array containing {@code values}
     */
    public static NativeFloatArray copyOf(float[] values) {
        Objects.requireNonNull(values, "values");
        NativeFloatArray array = allocate(values.length);
        array.copyFrom(values);
        return array;
    }

    /**
     * Returns the number of elements in this array.
     *
     * @return element count
     */
    public int length() {
        ensureOpen();
        return length;
    }

    /**
     * Returns the underlying memory segment passed to native functions.
     *
     * @return native memory segment
     */
    public MemorySegment segment() {
        ensureOpen();
        return segment;
    }

    /**
     * Copies all values from a Java heap array into this native array.
     *
     * @param source source array with the same length as this native array
     * @throws CppBridgeException if the source length does not match
     */
    public void copyFrom(float[] source) {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        if (source.length != length) {
            throw new CppBridgeException("Source array length mismatch: expected " + length + ", got " + source.length);
        }
        segment.copyFrom(MemorySegment.ofArray(source));
    }

    /**
     * Copies all values from this native array into a Java heap array.
     *
     * @param target target array with the same length as this native array
     * @throws CppBridgeException if the target length does not match
     */
    public void copyTo(float[] target) {
        ensureOpen();
        Objects.requireNonNull(target, "target");
        if (target.length != length) {
            throw new CppBridgeException("Target array length mismatch: expected " + length + ", got " + target.length);
        }
        MemorySegment.ofArray(target).copyFrom(segment);
    }

    /**
     * Copies this native array into a new Java heap array.
     *
     * @return Java heap copy of this array
     */
    public float[] toArray() {
        ensureOpen();
        float[] copy = new float[length];
        copyTo(copy);
        return copy;
    }

    /**
     * Reads one element from native memory.
     *
     * @param index element index
     * @return element value
     */
    public float get(int index) {
        ensureOpen();
        checkIndex(index);
        return segment.getAtIndex(ValueLayout.JAVA_FLOAT, index);
    }

    /**
     * Writes one element to native memory.
     *
     * @param index element index
     * @param value new element value
     */
    public void set(int index, float value) {
        ensureOpen();
        checkIndex(index);
        segment.setAtIndex(ValueLayout.JAVA_FLOAT, index, value);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index=" + index + ", length=" + length);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new CppBridgeException("NativeFloatArray is already closed");
        }
    }

    /**
     * Releases the native memory owned by this array. Calling this method more
     * than once has no effect.
     */
    @Override
    public void close() {
        if (!closed) {
            arena.close();
            closed = true;
        }
    }
}
