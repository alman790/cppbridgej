package dev.cppbridge.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class NativeApiMethods {
    private NativeApiMethods() {
    }

    static boolean isBindable(Method method) {
        return method.getDeclaringClass() != Object.class
                && !method.isDefault()
                && !Modifier.isStatic(method.getModifiers());
    }
}
