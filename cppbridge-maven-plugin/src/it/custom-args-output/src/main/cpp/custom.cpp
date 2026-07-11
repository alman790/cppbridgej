#ifndef CPPBRIDGE_CUSTOM_ARG
#error "CPPBRIDGE_CUSTOM_ARG is required"
#endif

#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

CPPBRIDGE_EXPORT int custom_value() {
    return 7;
}
