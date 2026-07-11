package dev.cppbridge.runtime;

import dev.cppbridge.ArrayDirection;
import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppArray;
import dev.cppbridge.annotations.CppFunction;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<Method, MethodHandle> handleCache = new ConcurrentHashMap<>();

    /**
     * Opens a native shared library and prepares symbol lookup.
     *
     * @param libraryPath path to the native shared library
     */
    public NativeInvocationHandler(Class<?> apiType, String libraryPath) {
        Objects.requireNonNull(apiType, "apiType");
        Objects.requireNonNull(libraryPath, "libraryPath");

        Path path = Path.of(libraryPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new CppBridgeException("Native library does not exist: " + path);
        }

        this.linker = Linker.nativeLinker();
        this.libraryArena = Arena.global();
        this.symbolLookup = SymbolLookup.libraryLookup(path, libraryArena);

        preloadHandles(apiType);
    }

    /**
     * Dispatches a proxy method call to the matching native symbol.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        Object[] safeArgs = args == null ? new Object[0] : args;
        MethodHandle handle = handleCache.computeIfAbsent(method, this::createHandle);

        try (Arena callArena = Arena.ofConfined()) {
            List<Object> nativeArgs = new ArrayList<>();
            List<ArrayCopyBack> copyBackTasks = new ArrayList<>();

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (safeArgs.length != parameterTypes.length) {
                throw new CppBridgeException("Invalid argument count for method " + method.getName() +
                        ": expected " + parameterTypes.length + ", got " + safeArgs.length);
            }

            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                Object value = safeArgs[i];

                if (NativeTypeMapper.isPrimitiveArray(parameterType)) {
                    if (value == null) {
                        throw new CppBridgeException("Array argument cannot be null: " + method.getName() + " parameter #" + i);
                    }

                    ArrayDirection direction = resolveArrayDirection(method, i);
                    MemorySegment segment = NativeArrayMemory.allocateAndCopy(callArena, value, direction);
                    nativeArgs.add(segment);
                    nativeArgs.add(NativeTypeMapper.arrayLength(value));

                    if (direction != ArrayDirection.IN) {
                        copyBackTasks.add(new ArrayCopyBack(segment, value));
                    }
                } else if (NativeTypeMapper.isManagedNativeArray(parameterType)) {
                    if (value == null) {
                        throw new CppBridgeException("Native array argument cannot be null: " + method.getName() + " parameter #" + i);
                    }
                    nativeArgs.add(NativeArrayMemory.segmentOfManagedNativeArray(value));
                    nativeArgs.add(NativeArrayMemory.lengthOfManagedNativeArray(value));
                } else {
                    nativeArgs.add(value);
                }
            }

            Object result = handle.invokeWithArguments(nativeArgs);

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
                + " -> " + resolveNativeName(method), throwable);
    }

    private void preloadHandles(Class<?> apiType) {
        Arrays.stream(apiType.getMethods())
                .filter(NativeApiMethods::isBindable)
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> handleCache.computeIfAbsent(method, this::createHandle));
    }

    private MethodHandle createHandle(Method method) {
        String nativeName = resolveNativeName(method);
        MemorySegment address = symbolLookup.find(nativeName)
                .orElseThrow(() -> new CppBridgeException("Native symbol not found: " + nativeName));

        FunctionDescriptor descriptor = createDescriptor(method);
        return linker.downcallHandle(address, descriptor);
    }

    private static String resolveNativeName(Method method) {
        CppFunction function = method.getAnnotation(CppFunction.class);
        if (function == null || function.value().isBlank()) {
            return method.getName();
        }
        return function.value();
    }

    private static ArrayDirection resolveArrayDirection(Method method, int parameterIndex) {
        CppArray annotation = method.getParameters()[parameterIndex].getAnnotation(CppArray.class);
        return annotation == null ? ArrayDirection.IN_OUT : annotation.value();
    }

    private static FunctionDescriptor createDescriptor(Method method) {
        List<MemoryLayout> argumentLayouts = new ArrayList<>();

        for (Class<?> parameterType : method.getParameterTypes()) {
            if (NativeTypeMapper.isArrayLike(parameterType)) {
                argumentLayouts.add(ValueLayout.ADDRESS);
                argumentLayouts.add(ValueLayout.JAVA_INT);
            } else {
                argumentLayouts.add(NativeTypeMapper.valueLayoutForScalar(parameterType));
            }
        }

        MemoryLayout[] args = argumentLayouts.toArray(MemoryLayout[]::new);
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return FunctionDescriptor.ofVoid(args);
        }

        return FunctionDescriptor.of(NativeTypeMapper.valueLayoutForScalar(returnType), args);
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "CppBridge proxy for " + proxy.getClass().getInterfaces()[0].getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
        };
    }

    private record ArrayCopyBack(MemorySegment segment, Object array) {
    }
}
