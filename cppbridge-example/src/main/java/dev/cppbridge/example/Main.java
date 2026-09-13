package dev.cppbridge.example;

import dev.cppbridge.CppBridge;
import dev.cppbridge.memory.NativeByteArray;
import dev.cppbridge.memory.NativeDoubleArray;
import dev.cppbridge.memory.NativeLongArray;

import java.util.Arrays;

/** Example command-line application that loads and calls the native library. */
public class Main {
    public static void main(String[] args) {
        System.out.println(CppBridge.inspect(FastMath.class).toText());
        RichTypesDemo.run();

        FastMath math = CppBridge.load(FastMath.class);

        System.out.println("sum(10, 20) = " + math.sum(10, 20));

        double[] values = {10.0, 20.0, 30.0, 40.0};
        System.out.println("average = " + math.average(values));

        math.multiplyEach(values, 2.5);
        System.out.println("after multiplyEach = " + Arrays.toString(values));

        try (NativeDoubleArray nativeValues =
                NativeDoubleArray.copyOf(new double[] {1.0, 2.0, 3.0, 4.0})) {
            math.multiplyEachNative(nativeValues, 10.0);
            System.out.println(
                    "after multiplyEachNative = " + Arrays.toString(nativeValues.toArray()));
        }

        long[] longValues = {1, 2, 3, 4, 5};
        System.out.println("sumLongArray = " + math.sumLongArray(longValues));

        try (NativeLongArray nativeLongValues =
                NativeLongArray.copyOf(new long[] {1, 2, 3, 4, 5})) {
            System.out.println("sumLongArrayNative = " + math.sumLongArrayNative(nativeLongValues));
        }

        byte[] pixels = {(byte) 10, (byte) 100, (byte) 250};
        math.brighten(pixels, 20);
        int[] unsignedPixels = {
            Byte.toUnsignedInt(pixels[0]),
            Byte.toUnsignedInt(pixels[1]),
            Byte.toUnsignedInt(pixels[2])
        };
        System.out.println("after brighten = " + Arrays.toString(unsignedPixels));

        try (NativeByteArray nativePixels =
                NativeByteArray.copyOf(new byte[] {(byte) 10, (byte) 100, (byte) 250})) {
            math.brightenNative(nativePixels, 20);
            int[] unsignedNativePixels = {
                nativePixels.getUnsigned(0),
                nativePixels.getUnsigned(1),
                nativePixels.getUnsigned(2)
            };
            System.out.println("after brightenNative = " + Arrays.toString(unsignedNativePixels));
        }
    }
}
