package dev.cppbridge.annotations;

import java.lang.annotation.*;

/** Maps a void Java method to an int32_t native status: zero succeeds, other values throw. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CppStatus {
    /** Name of a no-argument function returning a borrowed, NUL-terminated UTF-8 error message. */
    String error();

    int maxMessageBytes() default 1024;
}
