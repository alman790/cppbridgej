package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.*;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.memory.*;

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
    private NativeApiMethods() {}

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
            case "equals" ->
                    Arrays.equals(method.getParameterTypes(), new Class<?>[] {Object.class});
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
                if (parameter.isAnnotationPresent(CppArray.class)
                        && !NativeTypeMapper.isHeapArray(type)) {
                    throw new CppBridgeException(
                            "@CppArray requires a supported heap array: " + parameter.getName());
                }
                CppString string = parameter.getAnnotation(CppString.class);
                if (string != null && (type != String.class || string.maxBytes() <= 0)) {
                    throw new CppBridgeException(
                            "@CppString requires String and a positive byte bound");
                }
                if (NativeTypeMapper.isArrayLike(type)) {
                    if (NativeTypeMapper.isHeapArray(type))
                        NativeCodec.layout(type.getComponentType());
                    if (type == NativeStructArray.class) validateStructParameter(parameter);
                    arguments.add(ValueLayout.ADDRESS);
                    arguments.add(ValueLayout.JAVA_INT);
                } else {
                    if (type == String.class
                            || type == NativeStruct.class
                            || type.isAnnotationPresent(CppCallback.class)) {
                        if (type.isAnnotationPresent(CppCallback.class))
                            NativeCallback.descriptor(type);
                        if (type == NativeStruct.class) validateStructParameter(parameter);
                        arguments.add(ValueLayout.ADDRESS);
                    } else arguments.add(NativeCodec.layout(type));
                }
            }
            MemoryLayout[] layouts = arguments.toArray(MemoryLayout[]::new);
            Class<?> result = method.getReturnType();
            CppStatus status = method.getAnnotation(CppStatus.class);
            if (status != null) {
                if (result != void.class
                        || status.error().isBlank()
                        || status.maxMessageBytes() <= 0
                        || method.isAnnotationPresent(CppString.class)) {
                    throw new CppBridgeException(
                            "@CppStatus requires void return, an error symbol and a positive"
                                + " message bound");
                }
                return FunctionDescriptor.of(ValueLayout.JAVA_INT, layouts);
            }
            CppString string = method.getAnnotation(CppString.class);
            if (result == String.class) {
                if (string == null || string.maxBytes() <= 0)
                    throw new CppBridgeException(
                            "String return requires @CppString(maxBytes = ...)");
                return FunctionDescriptor.of(ValueLayout.ADDRESS, layouts);
            }
            if (string != null) throw new CppBridgeException("@CppString requires a String return");
            return result == void.class || result == Void.class
                    ? FunctionDescriptor.ofVoid(layouts)
                    : FunctionDescriptor.of(NativeCodec.layout(result), layouts);
        } catch (CppBridgeException exception) {
            throw new CppBridgeException(
                    "Invalid native signature for "
                            + method.toGenericString()
                            + ": "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static void validateStructParameter(java.lang.reflect.Parameter parameter) {
        if (!(parameter.getParameterizedType()
                        instanceof java.lang.reflect.ParameterizedType generic)
                || !(generic.getActualTypeArguments()[0] instanceof Class<?> record)) {
            throw new CppBridgeException(
                    "Native struct parameter requires a concrete record type: "
                            + parameter.getName());
        }
        StructType.ofRecord(record);
    }
}
