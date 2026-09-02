package dev.cppbridge.example;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.memory.NativeByteArray;
import dev.cppbridge.memory.NativeDoubleArray;
import dev.cppbridge.memory.NativeLongArray;

/**
 * Example CppBridgeJ API backed by {@code src/main/cpp/fastmath.cpp}.
 */
@CppModule(libraryName = "fastmath")
public interface FastMath {
    @CppFunction("sum_int")
    int sum(int a, int b);

    @CppFunction("average_double")
    double average(@CppArray(ArrayDirection.IN) double[] values);

    @CppFunction("multiply_each_double")
    void multiplyEach(double[] values, double factor);

    @CppFunction("multiply_each_double")
    void multiplyEachNative(NativeDoubleArray values, double factor);

    @CppFunction("sum_long_array")
    long sumLongArray(@CppArray(ArrayDirection.IN) long[] values);

    @CppFunction("sum_long_array")
    long sumLongArrayNative(NativeLongArray values);

    @CppFunction("brighten_bytes")
    void brighten(byte[] pixels, int amount);

    @CppFunction("brighten_bytes")
    void brightenNative(NativeByteArray pixels, int amount);
}
