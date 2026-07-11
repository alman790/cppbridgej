package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.diagnostics.BindingReport;
import dev.cppbridge.diagnostics.BindingReportEntry;
import dev.cppbridge.diagnostics.BindingStatus;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Creates diagnostic reports for native bindings without invoking API methods.
 *
 * <p>This class is used by {@code CppBridge.inspect(...)}. It checks whether
 * the selected native library exists, whether expected symbols are exported,
 * and whether Java method signatures can be mapped to native signatures.</p>
 */
public final class NativeBindingInspector {
    private NativeBindingInspector() {
    }

    /**
     * Inspects an annotated API type against the supplied native library.
     *
     * @param apiType API interface to inspect
     * @param module module annotation from {@code apiType}
     * @param libraryPath native shared-library path
     * @return binding report for all abstract API methods
     */
    public static BindingReport inspect(Class<?> apiType, CppModule module, String libraryPath) {
        Path path = Path.of(libraryPath).toAbsolutePath().normalize();
        boolean libraryExists = Files.exists(path);
        List<BindingReportEntry> entries = new ArrayList<>();

        SymbolLookup lookup = null;
        if (libraryExists) {
            try {
                lookup = SymbolLookup.libraryLookup(path, Arena.global());
            } catch (Throwable throwable) {
                return failedReport(apiType, module, path, "Could not open native library: " + throwable.getMessage());
            }
        }

        Method[] methods = apiType.getMethods();
        List<Method> apiMethods = new ArrayList<>();
        for (Method method : methods) {
            if (NativeApiMethods.isBindable(method)) {
                apiMethods.add(method);
            }
        }
        apiMethods.sort(Comparator.comparing(Method::getName));

        for (Method method : apiMethods) {
            String nativeSymbol = resolveNativeName(method);
            String javaSignature = javaSignature(method);
            try {
                String nativeSignature = nativeSignature(method, nativeSymbol);

                if (!libraryExists) {
                    entries.add(new BindingReportEntry(
                            javaSignature,
                            nativeSymbol,
                            nativeSignature,
                            BindingStatus.LIBRARY_NOT_FOUND,
                            "Run the C++ compilation step first."
                    ));
                    continue;
                }

                Optional<?> symbol = lookup.find(nativeSymbol);
                entries.add(new BindingReportEntry(
                        javaSignature,
                        nativeSymbol,
                        nativeSignature,
                        symbol.isPresent() ? BindingStatus.OK : BindingStatus.MISSING_SYMBOL,
                        symbol.isPresent() ? "" : "Native library does not export this symbol."
                ));
            } catch (CppBridgeException exception) {
                entries.add(new BindingReportEntry(
                        javaSignature,
                        nativeSymbol,
                        "<unsupported>",
                        BindingStatus.UNSUPPORTED_SIGNATURE,
                        exception.getMessage()
                ));
            } catch (Throwable throwable) {
                entries.add(new BindingReportEntry(
                        javaSignature,
                        nativeSymbol,
                        "<inspection failed>",
                        BindingStatus.INSPECTION_FAILED,
                        throwable.getMessage()
                ));
            }
        }

        return new BindingReport(apiType.getName(), module.mode().name(), path.toString(), libraryExists, entries);
    }

    private static BindingReport failedReport(Class<?> apiType, CppModule module, Path path, String message) {
        return new BindingReport(
                apiType.getName(),
                module.mode().name(),
                path.toString(),
                Files.exists(path),
                List.of(new BindingReportEntry("<library>", "<open>", "<open>", BindingStatus.INSPECTION_FAILED, message))
        );
    }

    private static String resolveNativeName(Method method) {
        CppFunction function = method.getAnnotation(CppFunction.class);
        if (function == null || function.value().isBlank()) {
            return method.getName();
        }
        return function.value();
    }

    private static String javaSignature(Method method) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Class<?> parameterType : method.getParameterTypes()) {
            joiner.add(simpleName(parameterType));
        }
        return simpleName(method.getReturnType()) + " " + method.getName() + "(" + joiner + ")";
    }

    private static String nativeSignature(Method method, String nativeSymbol) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (NativeTypeMapper.isArrayLike(parameterType)) {
                joiner.add(nativePointerType(parameterType));
                joiner.add("int length");
            } else {
                joiner.add(nativeScalarType(parameterType));
            }
        }
        return nativeScalarType(method.getReturnType()) + " " + nativeSymbol + "(" + joiner + ")";
    }

    private static String nativePointerType(Class<?> type) {
        Class<?> componentType = type.isArray() ? type.getComponentType() : managedNativeArrayComponent(type);
        return nativeScalarType(componentType) + "*";
    }

    private static Class<?> managedNativeArrayComponent(Class<?> type) {
        return switch (type.getName()) {
            case "dev.cppbridge.memory.NativeByteArray" -> byte.class;
            case "dev.cppbridge.memory.NativeIntArray" -> int.class;
            case "dev.cppbridge.memory.NativeLongArray" -> long.class;
            case "dev.cppbridge.memory.NativeFloatArray" -> float.class;
            case "dev.cppbridge.memory.NativeDoubleArray" -> double.class;
            default -> throw new CppBridgeException("Unsupported native array type: " + type.getName());
        };
    }

    private static String nativeScalarType(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return "void";
        }
        if (type == byte.class || type == Byte.class) {
            return "int8_t";
        }
        if (type == int.class || type == Integer.class) {
            return "int";
        }
        if (type == long.class || type == Long.class) {
            return "int64_t";
        }
        if (type == float.class || type == Float.class) {
            return "float";
        }
        if (type == double.class || type == Double.class) {
            return "double";
        }
        throw new CppBridgeException("Unsupported scalar type: " + type.getName());
    }

    private static String simpleName(Class<?> type) {
        if (type.isArray()) {
            return simpleName(type.getComponentType()) + "[]";
        }
        return type.getSimpleName();
    }
}
