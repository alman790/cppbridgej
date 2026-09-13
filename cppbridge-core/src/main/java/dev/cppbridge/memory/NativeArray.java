package dev.cppbridge.memory;

import java.lang.foreign.MemorySegment;

/** An owned native buffer passed as a pointer followed by an int32_t element count. */
public interface NativeArray extends AutoCloseable {
    MemorySegment segment();

    int length();

    @Override
    void close();
}
