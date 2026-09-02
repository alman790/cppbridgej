#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

#include <cstdint>

CPPBRIDGE_EXPORT std::int32_t present_symbol() {
    return 1;
}
