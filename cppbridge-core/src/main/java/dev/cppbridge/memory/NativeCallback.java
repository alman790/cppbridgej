package dev.cppbridge.memory;

import dev.cppbridge.CppBridgeException;
import dev.cppbridge.annotations.CppCallback;
import dev.cppbridge.runtime.NativeCodec;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Java callback with an explicit native lifetime. Unregister it and join native callers before
 * closing it. Failures are contained at the upcall boundary and reported by checkFailure(); native
 * code receives a zero value on failure.
 */
public final class NativeCallback<T> implements AutoCloseable {
    private static final MethodHandle DISPATCH;

    static {
        try {
            DISPATCH =
                    MethodHandles.lookup()
                            .findVirtual(
                                    NativeCallback.class,
                                    "dispatch",
                                    MethodType.methodType(Object.class, Object[].class));
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private final Arena arena;
    private final MethodHandle target;
    private final Class<?>[] parameters;
    private final Class<?> resultType;
    private final Object failureResult;
    private final ThreadLocal<MemorySegment> returnStorage;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final MemorySegment pointer;

    private NativeCallback(Class<T> type, T callback) {
        Objects.requireNonNull(callback, "callback");
        Method method = method(type);
        if (!type.isInstance(callback))
            throw new CppBridgeException("Callback does not implement " + type.getName());
        FunctionDescriptor descriptor = descriptor(type);
        parameters = method.getParameterTypes();
        resultType = method.getReturnType();
        try {
            if (!method.trySetAccessible()) throw new IllegalAccessException(type.getName());
            target = MethodHandles.lookup().unreflect(method).bindTo(callback);
        } catch (IllegalAccessException error) {
            throw new CppBridgeException(
                    "Open callback package to CppBridgeJ: " + type.getName(), error);
        }
        arena = Arena.ofShared();
        try {
            failureResult = zero(descriptor);
            returnStorage =
                    descriptor.returnLayout().orElse(null) instanceof GroupLayout group
                            ? ThreadLocal.withInitial(() -> arena.allocate(group))
                            : null;
            MethodHandle handler =
                    DISPATCH.bindTo(this)
                            .asCollector(Object[].class, parameters.length)
                            .asType(descriptor.toMethodType());
            pointer = Linker.nativeLinker().upcallStub(handler, descriptor, arena);
        } catch (RuntimeException | Error error) {
            arena.close();
            throw error;
        }
    }

    public static <T> NativeCallback<T> create(Class<T> type, T callback) {
        return new NativeCallback<>(type, callback);
    }

    private static Method method(Class<?> type) {
        if (!type.isInterface()
                || !type.isAnnotationPresent(CppCallback.class)
                || type.isSealed()
                || type.getTypeParameters().length != 0) {
            throw new CppBridgeException(
                    "@CppCallback requires a non-generic, non-sealed functional interface: "
                            + type.getName());
        }
        List<Method> methods =
                Arrays.stream(type.getMethods())
                        .filter(m -> Modifier.isAbstract(m.getModifiers()))
                        .filter(m -> !isObjectMethod(m))
                        .toList();
        if (methods.size() != 1)
            throw new CppBridgeException(
                    "Callback requires exactly one abstract method: " + type.getName());
        return methods.getFirst();
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    /** Validates a callback signature before any native library is called. */
    public static FunctionDescriptor descriptor(Class<?> type) {
        Method method = method(type);
        MemoryLayout[] arguments =
                Arrays.stream(method.getParameterTypes())
                        .map(NativeCodec::layout)
                        .toArray(MemoryLayout[]::new);
        return method.getReturnType() == void.class
                ? FunctionDescriptor.ofVoid(arguments)
                : FunctionDescriptor.of(NativeCodec.layout(method.getReturnType()), arguments);
    }

    private Object zero(FunctionDescriptor descriptor) {
        if (descriptor.returnLayout().isEmpty()) return null;
        MemoryLayout layout = descriptor.returnLayout().orElseThrow();
        if (layout instanceof GroupLayout) return arena.allocate(layout);
        Class<?> carrier = ((ValueLayout) layout).carrier();
        if (carrier == MemorySegment.class) return MemorySegment.NULL;
        return Array.get(Array.newInstance(carrier, 1), 0);
    }

    private Object dispatch(Object[] arguments) {
        try {
            for (int i = 0; i < arguments.length; i++)
                arguments[i] = NativeCodec.fromNative(parameters[i], arguments[i]);
            Object result = target.invokeWithArguments(arguments);
            if (returnStorage != null) {
                MemorySegment storage = returnStorage.get();
                StructType.ofRecord(resultType).writeValue(storage, result);
                return storage;
            }
            return resultType == void.class
                    ? null
                    : NativeCodec.toNative(resultType, result, arena);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            return failureResult;
        }
    }

    /** Returns a function pointer valid until close(). */
    public MemorySegment segment() {
        if (!arena.scope().isAlive())
            throw new CppBridgeException("NativeCallback is already closed");
        return pointer;
    }

    /** Retrieves and clears the first failure recorded since the last check. */
    public void checkFailure() {
        Throwable error = failure.getAndSet(null);
        if (error != null) throw new CppBridgeException("Java callback failed", error);
    }

    @Override
    public synchronized void close() {
        if (arena.scope().isAlive()) arena.close();
    }
}
