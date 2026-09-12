package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class NativeApiMethods {
    private NativeApiMethods() {
    }

    static boolean isBindable(Method method) {
        return !isObjectMethod(method)
                && !method.isDefault()
                && !Modifier.isStatic(method.getModifiers());
    }

    static List<Method> bindableMethods(Class<?> apiType) {
        return Arrays.stream(apiType.getMethods())
                .filter(NativeApiMethods::isBindable)
                .sorted(Comparator.comparing(Method::toGenericString))
                .toList();
    }

    static boolean isObjectMethod(Method method) {
        return switch (method.getName()) {
            case "toString", "hashCode" -> method.getParameterCount() == 0;
            case "equals" -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{Object.class});
            default -> false;
        };
    }

    static String nativeName(Method method) {
        CppFunction function = method.getAnnotation(CppFunction.class);
        return function == null || function.value().isBlank() ? method.getName() : function.value();
    }

    static FunctionDescriptor descriptor(Method method) {
        try {
            List<MemoryLayout> arguments = new ArrayList<>();
            for (var parameter : method.getParameters()) {
                Class<?> type = parameter.getType();
                if (parameter.isAnnotationPresent(CppArray.class) && !NativeTypeMapper.isPrimitiveArray(type)) {
                    throw new CppBridgeException("@CppArray requires a supported primitive heap array: " + parameter.getName());
                }
                if (NativeTypeMapper.isArrayLike(type)) {
                    arguments.add(ValueLayout.ADDRESS);
                    arguments.add(ValueLayout.JAVA_INT);
                } else {
                    arguments.add(NativeTypeMapper.valueLayoutForScalar(type));
                }
            }
            MemoryLayout[] layouts = arguments.toArray(MemoryLayout[]::new);
            Class<?> result = method.getReturnType();
            return result == void.class || result == Void.class
                    ? FunctionDescriptor.ofVoid(layouts)
                    : FunctionDescriptor.of(NativeTypeMapper.valueLayoutForScalar(result), layouts);
        } catch (CppBridgeException exception) {
            throw new CppBridgeException("Invalid native signature for " + method.toGenericString()
                    + ": " + exception.getMessage(), exception);
        }
    }
}
