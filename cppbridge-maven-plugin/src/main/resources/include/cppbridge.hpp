#ifndef CPPBRIDGE_HPP
#define CPPBRIDGE_HPP

#include <cstdint>
#include <cstddef>
#include <exception>
#include <utility>

#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace cppbridge {
namespace detail {
inline char* error_buffer() noexcept {
    static thread_local char message[1024]{};
    return message;
}
inline void set_error(const char* text) noexcept {
    char* message = error_buffer();
    std::size_t i = 0;
    if (text != nullptr) {
        for (; i < 1023 && text[i] != '\0'; ++i) message[i] = text[i];
    }
    message[i] = '\0';
}
}

// Read on the calling thread before another guarded operation replaces the error.
inline const char* last_error() noexcept { return detail::error_buffer(); }

template<class Function>
std::int32_t guard(Function&& operation) noexcept {
    detail::set_error("");
    try {
        std::forward<Function>(operation)();
        return 0;
    } catch (const std::exception& error) {
        detail::set_error(error.what());
        return 1;
    } catch (...) {
        detail::set_error("Unknown C++ exception");
        return 2;
    }
}
}
#endif
