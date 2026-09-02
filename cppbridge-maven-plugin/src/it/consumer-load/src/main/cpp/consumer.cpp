#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

CPPBRIDGE_EXPORT int answer_value() {
    return 123;
}

CPPBRIDGE_EXPORT void add_each(int* values, int length, int delta) {
    for (int i = 0; i < length; i++) {
        values[i] += delta;
    }
}
