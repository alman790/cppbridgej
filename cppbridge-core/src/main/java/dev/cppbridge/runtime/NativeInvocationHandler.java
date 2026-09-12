package dev.cppbridge.runtime;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppArray;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation handler used by CppBridgeJ dynamic proxies.
 *
 * <p>The handler maps Java calls to downcall method handles created by the
 * Foreign Function &amp; Memory API. Primitive arrays are copied to temporary
 * native memory for a call. Managed native arrays are passed directly.</p>
 */
public final class NativeInvocationHandler implements InvocationHandler {
    private final Linker linker;
    private final Arena libraryArena;
    private final SymbolLookup symbolLookup;
    private final Map<Method, Binding> bindings;

    /**
     * Opens a native shared library and prepares symbol lookup.
     *
     * @param libraryPath path to the native shared library
     */
    public NativeInvocationHandler(Class<?> apiType, String libraryPath) {
        Objects.requireNonNull(apiType, "apiType");
        Objects.requireNonNull(libraryPath, "libraryPath");

        if (libraryPath.isBlank()) {
            throw new CppBridgeException("Native library path must not be blank");
        }
        Map<Method, FunctionDescriptor> descriptors = new LinkedHashMap<>();
        for (Method method : NativeApiMethods.bindableMethods(apiType)) {
            descriptors.put(method, NativeApiMethods.descriptor(method));
        }

        Path path = Path.of(libraryPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new CppBridgeException("Native library does not exist: " + path);
        }

        this.libraryArena = Arena.global();
        try {
            this.linker = Linker.nativeLinker();
            this.symbolLookup = SymbolLookup.libraryLookup(path, libraryArena);
        } catch (IllegalCallerException exception) {
            throw new CppBridgeException("Native access is disabled. Start Java with "
                    + "--enable-native-access=ALL-UNNAMED", exception);
        } catch (IllegalArgumentException | UnsatisfiedLinkError exception) {
            throw new CppBridgeException("Cannot open native library: " + path
                    + ". Check its architecture and dependent libraries. " + exception.getMessage(), exception);
        }

        Map<Method, Binding> resolved = new LinkedHashMap<>();
        descriptors.forEach((method, descriptor) -> {
            String name = NativeApiMethods.nativeName(method);
            MemorySegment address = symbolLookup.find(name).orElseThrow(() ->
                    new CppBridgeException("Native symbol not found: " + name + " for "
                            + method.toGenericString() + " in " + path));
            ArrayDirection[] directions = new ArrayDirection[method.getParameterCount()];
            for (int i = 0; i < directions.length; i++) {
                directions[i] = resolveArrayDirection(method, i);
            }
            resolved.put(method, new Binding(linker.downcallHandle(address, descriptor),
                    method.getParameterTypes(), directions));
        });
        this.bindings = Map.copyOf(resolved);
    }

    /**
     * Dispatches a proxy method call to the matching native symbol.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (NativeApiMethods.isObjectMethod(method)) {
            return invokeObjectMethod(proxy, method, args);
        }
        if (method.isDefault()) {
            if (!method.canAccess(proxy)) {
                return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                        .unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy)
                        .invokeWithArguments(args == null ? new Object[0] : args);
            }
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        Object[] safeArgs = args == null ? new Object[0] : args;
        Binding binding = bindings.get(method);
        if (binding == null) {
            throw new CppBridgeException("No native binding for " + method.toGenericString());
        }

        try (Arena callArena = Arena.ofConfined()) {
            List<Object> nativeArgs = new ArrayList<>();
            List<ArrayCopyBack> copyBackTasks = new ArrayList<>();

            Class<?>[] parameterTypes = binding.parameterTypes();
            if (safeArgs.length != parameterTypes.length) {
                throw new CppBridgeException("Invalid argument count for method " + method.getName() +
                        ": expected " + parameterTypes.length + ", got " + safeArgs.length);
            }

            // One Java array must remain one native pointer, even when parameter directions differ.
            Map<Object, ArrayDirection> directions = new IdentityHashMap<>();
            for (int i = 0; i < parameterTypes.length; i++) {
                if (NativeTypeMapper.isPrimitiveArray(parameterTypes[i]) && safeArgs[i] != null) {
                    directions.merge(safeArgs[i], binding.directions()[i],
                            (left, right) -> left == right ? left : ArrayDirection.IN_OUT);
                }
            }
            Map<Object, MemorySegment> segments = new IdentityHashMap<>();

            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                Object value = safeArgs[i];

                if (NativeTypeMapper.isPrimitiveArray(parameterType)) {
                    if (value == null) {
                        throw new CppBridgeException("Array argument cannot be null: " + method.getName() + " parameter #" + i);
                    }

                    MemorySegment segment = segments.get(value);
                    if (segment == null) {
                        ArrayDirection direction = directions.get(value);
                        segment = NativeArrayMemory.allocateAndCopy(callArena, value, direction);
                        segments.put(value, segment);
                        if (direction != ArrayDirection.IN) {
                            copyBackTasks.add(new ArrayCopyBack(segment, value));
                        }
                    }
                    nativeArgs.add(segment);
                    nativeArgs.add(NativeTypeMapper.arrayLength(value));
                } else if (NativeTypeMapper.isManagedNativeArray(parameterType)) {
                    if (value == null) {
                        throw new CppBridgeException("Native array argument cannot be null: " + method.getName() + " parameter #" + i);
                    }
                    nativeArgs.add(NativeArrayMemory.segmentOfManagedNativeArray(value));
                    nativeArgs.add(NativeArrayMemory.lengthOfManagedNativeArray(value));
                } else {
                    if (value == null) {
                        throw new CppBridgeException("Scalar argument cannot be null: " + method.getName()
                                + " parameter #" + i);
                    }
                    nativeArgs.add(value);
                }
            }

            Object result = binding.handle().invokeWithArguments(nativeArgs);

            for (ArrayCopyBack task : copyBackTasks) {
                NativeArrayMemory.copyBack(task.segment(), task.array());
            }

            return result;
        } catch (CppBridgeException exception) {
            throw exception;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw wrapNativeFailure(method, throwable);
        }
    }

    static CppBridgeException wrapNativeFailure(Method method, Throwable throwable) throws Throwable {
        if (throwable instanceof CppBridgeException exception) {
            throw exception;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new CppBridgeException("Native call failed: " + method.getName()
                + " -> " + NativeApiMethods.nativeName(method), throwable);
    }

    private static ArrayDirection resolveArrayDirection(Method method, int parameterIndex) {
        CppArray annotation = method.getParameters()[parameterIndex].getAnnotation(CppArray.class);
        return annotation == null ? ArrayDirection.IN_OUT : annotation.value();
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "CppBridge proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
        };
    }

    private record Binding(MethodHandle handle, Class<?>[] parameterTypes, ArrayDirection[] directions) {
    }

    private record ArrayCopyBack(MemorySegment segment, Object array) {
    }
}
