package dev.cppbridge;

/** An enum with an explicit int32_t native representation; ordinals are never used. */
public interface CppEnum {
    /** Returns the value declared by the matching C++ enum with an int32_t underlying type. */
    int nativeValue();
}
