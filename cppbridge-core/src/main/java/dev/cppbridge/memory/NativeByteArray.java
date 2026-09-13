package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Off-heap {@code byte[]} wrapper for image, audio, and binary kernels.
 *
 * <p>The array owns a confined {@link Arena}. Native memory is released when {@link #close()} is
 * called. Use try-with-resources for deterministic cleanup.
 *
 * <p>This type is intended for hot paths where the same data is passed to C++ repeatedly. It avoids
 * copying a Java heap array into native memory for every native call.
 */
public final class NativeByteArray implements NativeArray {
    private final Arena arena;
    private final MemorySegment segment;
    private final int length;
    private boolean closed;

    private NativeByteArray(Arena arena, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        this.arena = Objects.requireNonNull(arena, "arena");
        this.length = length;
        this.segment =
                arena.allocate(
                        ValueLayout.JAVA_BYTE.byteSize() * length,
                        ValueLayout.JAVA_BYTE.byteAlignment());
    }

    /**
     * Allocates a zero-initialized native array.
     *
     * @param length number of elements
     * @return allocated native array
     */
    public static NativeByteArray allocate(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        Arena arena = Arena.ofConfined();
        try {
            return new NativeByteArray(arena, length);
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
    public static NativeByteArray copyOf(byte[] values) {
        Objects.requireNonNull(values, "values");
        NativeByteArray array = allocate(values.length);
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
    public void copyFrom(byte[] source) {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        if (source.length != length) {
            throw new CppBridgeException(
                    "Source array length mismatch: expected " + length + ", got " + source.length);
        }
        segment.copyFrom(MemorySegment.ofArray(source));
    }

    /**
     * Copies all values from this native array into a Java heap array.
     *
     * @param target target array with the same length as this native array
     * @throws CppBridgeException if the target length does not match
     */
    public void copyTo(byte[] target) {
        ensureOpen();
        Objects.requireNonNull(target, "target");
        if (target.length != length) {
            throw new CppBridgeException(
                    "Target array length mismatch: expected " + length + ", got " + target.length);
        }
        MemorySegment.ofArray(target).copyFrom(segment);
    }

    /**
     * Copies this native array into a new Java heap array.
     *
     * @return Java heap copy of this array
     */
    public byte[] toArray() {
        ensureOpen();
        byte[] copy = new byte[length];
        copyTo(copy);
        return copy;
    }

    /**
     * Reads one element from native memory.
     *
     * @param index element index
     * @return element value
     */
    public byte get(int index) {
        ensureOpen();
        checkIndex(index);
        return segment.getAtIndex(ValueLayout.JAVA_BYTE, index);
    }

    /**
     * Reads an element as an unsigned byte value.
     *
     * @param index element index
     * @return value in the {@code [0, 255]} range
     */
    public int getUnsigned(int index) {
        return Byte.toUnsignedInt(get(index));
    }

    /**
     * Writes one element to native memory.
     *
     * @param index element index
     * @param value new element value
     */
    public void set(int index, byte value) {
        ensureOpen();
        checkIndex(index);
        segment.setAtIndex(ValueLayout.JAVA_BYTE, index, value);
    }

    /**
     * Writes an unsigned byte value to this array.
     *
     * @param index element index
     * @param value value in the {@code [0, 255]} range
     * @throws IllegalArgumentException if {@code value} is outside the unsigned byte range
     */
    public void setUnsigned(int index, int value) {
        ensureOpen();
        checkIndex(index);
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("unsigned byte value must be in [0, 255]: " + value);
        }
        segment.setAtIndex(ValueLayout.JAVA_BYTE, index, (byte) value);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index=" + index + ", length=" + length);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new CppBridgeException("NativeByteArray is already closed");
        }
    }

    /**
     * Releases the native memory owned by this array. Calling this method more than once has no
     * effect.
     */
    @Override
    public void close() {
        if (!closed) {
            arena.close();
            closed = true;
        }
    }
}
