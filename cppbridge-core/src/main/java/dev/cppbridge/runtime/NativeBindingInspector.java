package dev.cppbridge.runtime;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.annotations.CppStatus;
import dev.cppbridge.diagnostics.BindingReport;
import dev.cppbridge.diagnostics.BindingReportEntry;
import dev.cppbridge.diagnostics.BindingStatus;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Creates diagnostic reports for native bindings without invoking API methods.
 *
 * <p>This class is used by {@code CppBridge.inspect(...)}. It checks whether the selected native
 * library exists, whether expected symbols are exported, and whether Java method signatures can be
 * mapped to native signatures.
 */
public final class NativeBindingInspector {
    private NativeBindingInspector() {}

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
        try (Arena arena = Arena.ofConfined()) {
            return inspect(apiType, module, path, arena);
        }
    }

    private static BindingReport inspect(
            Class<?> apiType, CppModule module, Path path, Arena arena) {
        boolean libraryExists = Files.isRegularFile(path);
        List<BindingReportEntry> entries = new ArrayList<>();

        SymbolLookup lookup = null;
        if (libraryExists) {
            try {
                lookup = SymbolLookup.libraryLookup(path, arena);
            } catch (RuntimeException | UnsatisfiedLinkError throwable) {
                return failedReport(
                        apiType,
                        module,
                        path,
                        "Could not open native library: " + throwable.getMessage());
            }
        }

        for (Method method : NativeApiMethods.bindableMethods(apiType)) {
            String nativeSymbol = NativeApiMethods.nativeName(method);
            String javaSignature = javaSignature(method);
            try {
                NativeApiMethods.descriptor(method);
                String nativeSignature = nativeSignature(method, nativeSymbol);

                if (!libraryExists) {
                    entries.add(
                            new BindingReportEntry(
                                    javaSignature,
                                    nativeSymbol,
                                    nativeSignature,
                                    BindingStatus.LIBRARY_NOT_FOUND,
                                    "Run the C++ compilation step first."));
                    continue;
                }

                Optional<?> symbol = lookup.find(nativeSymbol);
                CppStatus status = method.getAnnotation(CppStatus.class);
                boolean missingError = status != null && lookup.find(status.error()).isEmpty();
                entries.add(
                        new BindingReportEntry(
                                javaSignature,
                                nativeSymbol,
                                nativeSignature,
                                symbol.isPresent() && !missingError
                                        ? BindingStatus.OK
                                        : BindingStatus.MISSING_SYMBOL,
                                missingError
                                        ? "Native error symbol not found: " + status.error()
                                        : symbol.isPresent()
                                                ? ""
                                                : "Native library does not export this symbol."));
            } catch (CppBridgeException exception) {
                entries.add(
                        new BindingReportEntry(
                                javaSignature,
                                nativeSymbol,
                                "<unsupported>",
                                BindingStatus.UNSUPPORTED_SIGNATURE,
                                exception.getMessage()));
            } catch (RuntimeException | UnsatisfiedLinkError throwable) {
                entries.add(
                        new BindingReportEntry(
                                javaSignature,
                                nativeSymbol,
                                "<inspection failed>",
                                BindingStatus.INSPECTION_FAILED,
                                throwable.getMessage()));
            }
        }

        return new BindingReport(
                apiType.getName(), module.mode().name(), path.toString(), libraryExists, entries);
    }

    private static BindingReport failedReport(
            Class<?> apiType, CppModule module, Path path, String message) {
        return new BindingReport(
                apiType.getName(),
                module.mode().name(),
                path.toString(),
                Files.isRegularFile(path),
                List.of(
                        new BindingReportEntry(
                                "<library>",
                                "<open>",
                                "<open>",
                                BindingStatus.INSPECTION_FAILED,
                                message)));
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
                joiner.add("int32_t length");
            } else {
                joiner.add(nativeScalarType(parameterType));
            }
        }
        return (method.isAnnotationPresent(CppStatus.class)
                        ? "int32_t"
                        : nativeScalarType(method.getReturnType()))
                + " "
                + nativeSymbol
                + "("
                + joiner
                + ")";
    }

    private static String nativePointerType(Class<?> type) {
        Class<?> componentType =
                type.isArray() ? type.getComponentType() : managedNativeArrayComponent(type);
        return nativeScalarType(componentType) + "*";
    }

    private static Class<?> managedNativeArrayComponent(Class<?> type) {
        return switch (type.getName()) {
            case "dev.cppbridge.memory.NativeByteArray" -> byte.class;
            case "dev.cppbridge.memory.NativeIntArray" -> int.class;
            case "dev.cppbridge.memory.NativeLongArray" -> long.class;
            case "dev.cppbridge.memory.NativeFloatArray" -> float.class;
            case "dev.cppbridge.memory.NativeDoubleArray" -> double.class;
            case "dev.cppbridge.memory.NativeShortArray" -> short.class;
            case "dev.cppbridge.memory.NativeCharArray" -> char.class;
            case "dev.cppbridge.memory.NativeBooleanArray" -> boolean.class;
            default -> void.class;
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
            return "int32_t";
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
        if (type == short.class || type == Short.class) return "int16_t";
        if (type == char.class || type == Character.class) return "uint16_t";
        if (type == boolean.class || type == Boolean.class) return "bool";
        if (type == String.class) return "const char*";
        if (type == java.lang.foreign.MemorySegment.class) return "void*";
        if (type == dev.cppbridge.memory.NativeStruct.class) return "struct*";
        if (NativeCodec.isStruct(type)) return "struct " + type.getSimpleName();
        if (NativeCodec.isEnum(type)) return "int32_t";
        if (type.isAnnotationPresent(dev.cppbridge.annotations.CppCallback.class))
            return "function*";
        throw new CppBridgeException("Unsupported scalar type: " + type.getName());
    }

    private static String simpleName(Class<?> type) {
        if (type.isArray()) {
            return simpleName(type.getComponentType()) + "[]";
        }
        return type.getSimpleName();
    }
}
