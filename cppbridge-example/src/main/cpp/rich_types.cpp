#include <cppbridge.hpp>
#include <cmath>
#include <functional>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
class History {
    std::string title_;
    std::vector<double> values_;
public:
    explicit History(const char* title) : title_(title) { }
    const char* title() const noexcept { return title_.c_str(); }
    void add(double value) {
        if (!std::isfinite(value)) throw std::invalid_argument("History values must be finite");
        values_.push_back(value);
    }
    template<class Function> void map(Function operation) {
        for (double& value : values_) value = operation(value);
    }
    double average() const {
        if (values_.empty()) throw std::logic_error("History is empty");
        return std::accumulate(values_.begin(), values_.end(), 0.0) / values_.size();
    }
};
}

struct RichPoint { double x; double y; };
CPPBRIDGE_EXPORT RichPoint rich_translate(RichPoint point, RichPoint offset) {
    return {point.x + offset.x, point.y + offset.y};
}
CPPBRIDGE_EXPORT const char* rich_error() noexcept { return cppbridge::last_error(); }
CPPBRIDGE_EXPORT std::int32_t history_create(const char* title, History** output) noexcept {
    return cppbridge::guard([&] {
        if (title == nullptr || output == nullptr) throw std::invalid_argument("Missing history arguments");
        *output = new History(title);
    });
}
CPPBRIDGE_EXPORT void history_destroy(History* history) noexcept { delete history; }
CPPBRIDGE_EXPORT const char* history_title(const History* history) noexcept { return history->title(); }
CPPBRIDGE_EXPORT std::int32_t history_add(History* history, double value) noexcept {
    return cppbridge::guard([&] { history->add(value); });
}
CPPBRIDGE_EXPORT std::int32_t history_map(History* history, double (*operation)(double)) noexcept {
    return cppbridge::guard([&] { history->map(std::function<double(double)>(operation)); });
}
CPPBRIDGE_EXPORT std::int32_t history_average(const History* history, double* output, std::int32_t length) noexcept {
    return cppbridge::guard([&] {
        if (output == nullptr || length != 1) throw std::invalid_argument("Expected one output element");
        *output = history->average();
    });
}
