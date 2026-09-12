#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

#include <cstdint>
#include "consumer_constants.hpp"

CPPBRIDGE_EXPORT std::int32_t answer_value() {
    return consumer_answer;
}

CPPBRIDGE_EXPORT void add_each(std::int32_t* values, std::int32_t length, std::int32_t delta) {
    for (std::int32_t i = 0; i < length; i++) {
        values[i] += delta;
    }
}
