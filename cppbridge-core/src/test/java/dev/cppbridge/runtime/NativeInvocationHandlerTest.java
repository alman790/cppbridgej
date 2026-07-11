package dev.cppbridge.runtime;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeInvocationHandlerTest {
    @Test
    void preservesExistingCppBridgeExceptionAndErrors() throws Throwable {
        Method method = Api.class.getMethod("average", double[].class);

        CppBridgeException existing = new CppBridgeException("existing");
        assertSame(existing, assertThrows(
                CppBridgeException.class,
                () -> NativeInvocationHandler.wrapNativeFailure(method, existing)
        ));

        AssertionError error = new AssertionError("serious");
        assertSame(error, assertThrows(
                AssertionError.class,
                () -> NativeInvocationHandler.wrapNativeFailure(method, error)
        ));
    }

    @Test
    void wrapsInvocationFailuresWithMethodAndSymbolContext() throws Throwable {
        Method method = Api.class.getMethod("average", double[].class);

        CppBridgeException wrapped = NativeInvocationHandler.wrapNativeFailure(
                method,
                new IllegalStateException("boom")
        );

        assertTrue(wrapped.getMessage().contains("average"));
        assertTrue(wrapped.getMessage().contains("average_double"));
    }

    interface Api {
        @CppFunction("average_double")
        double average(@CppArray(ArrayDirection.IN) double[] values);
    }
}
