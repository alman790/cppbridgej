#include <cmath>
#include <cstdint>

#ifdef _WIN32
#define CPPBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define CPPBRIDGE_EXPORT extern "C"
#endif

CPPBRIDGE_EXPORT double average_double(double* values, std::int32_t length) {
    if (length <= 0) {
        return 0.0;
    }

    double total = 0.0;
    for (std::int32_t i = 0; i < length; i++) {
        total += values[i];
    }
    return total / length;
}

CPPBRIDGE_EXPORT void multiply_each_double(double* values, std::int32_t length, double factor) {
    for (std::int32_t i = 0; i < length; i++) {
        values[i] *= factor;
    }
}

static inline double heavy_value(double x) {
    return std::sin(x) * std::cos(x) + std::sqrt(x + 1.0);
}

CPPBRIDGE_EXPORT void heavy_transform_double(double* values, std::int32_t length) {
    for (std::int32_t i = 0; i < length; i++) {
        values[i] = heavy_value(values[i]);
    }
}

CPPBRIDGE_EXPORT void three_step_pipeline_double(double* values, std::int32_t length, double factor) {
    for (std::int32_t i = 0; i < length; i++) {
        double x = heavy_value(values[i]);
        x *= factor;
        values[i] = heavy_value(x);
    }
}

CPPBRIDGE_EXPORT void brightness_u8(std::int8_t* pixels, std::int32_t length, std::int32_t delta) {
    for (std::int32_t i = 0; i < length; i++) {
        int current = static_cast<unsigned char>(pixels[i]);
        int next = current + delta;
        if (next > 255) {
            next = 255;
        } else if (next < 0) {
            next = 0;
        }
        pixels[i] = static_cast<std::int8_t>(next);
    }
}

CPPBRIDGE_EXPORT void invert_u8(std::int8_t* pixels, std::int32_t length) {
    for (std::int32_t i = 0; i < length; i++) {
        int current = static_cast<unsigned char>(pixels[i]);
        pixels[i] = static_cast<std::int8_t>(255 - current);
    }
}

CPPBRIDGE_EXPORT void threshold_u8(std::int8_t* pixels, std::int32_t length, std::int32_t threshold) {
    if (threshold < 0) {
        threshold = 0;
    } else if (threshold > 255) {
        threshold = 255;
    }

    for (std::int32_t i = 0; i < length; i++) {
        int current = static_cast<unsigned char>(pixels[i]);
        pixels[i] = static_cast<std::int8_t>(current >= threshold ? 255 : 0);
    }
}

CPPBRIDGE_EXPORT void image_pipeline_u8(std::int8_t* pixels, std::int32_t length, std::int32_t delta, std::int32_t threshold) {
    if (threshold < 0) {
        threshold = 0;
    } else if (threshold > 255) {
        threshold = 255;
    }

    for (std::int32_t i = 0; i < length; i++) {
        int current = static_cast<unsigned char>(pixels[i]);

        int bright = current + delta;
        if (bright > 255) {
            bright = 255;
        } else if (bright < 0) {
            bright = 0;
        }

        int inverted = 255 - bright;
        pixels[i] = static_cast<std::int8_t>(inverted >= threshold ? 255 : 0);
    }
}
