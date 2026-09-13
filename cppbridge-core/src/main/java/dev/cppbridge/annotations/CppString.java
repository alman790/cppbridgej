package dev.cppbridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds a borrowed, NUL-terminated UTF-8 result, including its terminator. The native allocation
 * must be readable through the terminator.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface CppString {
    int maxBytes();
}
