#include "cppbridge.hpp"
#include <cstddef>
#include <cstdarg>
#include <cstring>
#include <stdexcept>
#include <thread>
#include <vector>
#include <algorithm>

struct Point { double x; double y; };
struct Packet { std::int8_t tag; Point origin; std::int16_t samples[3]; bool enabled; };
struct Scene { Point points[2]; double matrix[4]; };
enum class Color : std::int32_t { red = 7, blue = 42 };
struct Settings { Color color; char16_t letter; bool active; };
struct Link { std::int32_t value; void* next; };
union Number { std::int32_t integer; float floating; };

CPPBRIDGE_EXPORT std::int64_t packet_size() { return sizeof(Packet); }
CPPBRIDGE_EXPORT std::int64_t packet_alignment() { return alignof(Packet); }
CPPBRIDGE_EXPORT std::int64_t packet_origin_offset() { return offsetof(Packet, origin); }
CPPBRIDGE_EXPORT std::int64_t packet_samples_offset() { return offsetof(Packet, samples); }
CPPBRIDGE_EXPORT std::int64_t packet_enabled_offset() { return offsetof(Packet, enabled); }
CPPBRIDGE_EXPORT Point move_point(Point point, double amount) { return {point.x + amount, point.y - amount}; }
CPPBRIDGE_EXPORT Packet transform_packet(Packet packet) {
    packet.origin.x += 2; packet.samples[1] += 3; packet.enabled = !packet.enabled; return packet;
}
CPPBRIDGE_EXPORT Scene transform_scene(Scene scene) { scene.points[1].y += 5; scene.matrix[2] *= 2; return scene; }
CPPBRIDGE_EXPORT void move_pointer(Point* point) { point->x += 10; point->y += 20; }
CPPBRIDGE_EXPORT void move_points(Point* points, std::int32_t length) { for (std::int32_t i=0;i<length;i++) move_pointer(points+i); }
CPPBRIDGE_EXPORT std::int32_t alias_points(Point* input, std::int32_t a, Point* output, std::int32_t b) {
    if (a>0 && b>0) { output[0] = input[0]; output[0].x += 3; } return input == output;
}
CPPBRIDGE_EXPORT void fill_points(Point* points, std::int32_t length) { for (std::int32_t i=0;i<length;i++) points[i] = {double(i), double(i+1)}; }
CPPBRIDGE_EXPORT Settings change_settings(Settings value) { value.color=Color::blue; value.letter=u'Ж'; value.active=!value.active; return value; }
CPPBRIDGE_EXPORT Color color_next(Color value) { return value == Color::red ? Color::blue : Color::red; }
CPPBRIDGE_EXPORT Color color_unknown() { return static_cast<Color>(999); }
CPPBRIDGE_EXPORT void paint(Color* colors, std::int32_t length) { for(std::int32_t i=0;i<length;i++) colors[i]=Color::blue; }
CPPBRIDGE_EXPORT bool boolean_not(bool value) { return !value; }
CPPBRIDGE_EXPORT std::int16_t short_inc(std::int16_t value) { return value+1; }
CPPBRIDGE_EXPORT char16_t char_echo(char16_t value) { return value; }
CPPBRIDGE_EXPORT void invert_bools(bool* values, std::int32_t length) { for(std::int32_t i=0;i<length;i++) values[i]=!values[i]; }
CPPBRIDGE_EXPORT void add_shorts(std::int16_t* values, std::int32_t length) { for(std::int32_t i=0;i<length;i++) values[i]+=3; }
CPPBRIDGE_EXPORT void replace_chars(char16_t* values, std::int32_t length) { for(std::int32_t i=0;i<length;i++) values[i]=u'Ж'; }
CPPBRIDGE_EXPORT std::int32_t utf8_length(const char* value) { return static_cast<std::int32_t>(std::strlen(value)); }
CPPBRIDGE_EXPORT const char* utf8_echo(const char* value) { return value; }
CPPBRIDGE_EXPORT const char* utf8_null() { return nullptr; }
CPPBRIDGE_EXPORT const char* utf8_bad() { static const char bad[] = {char(0xff),0}; return bad; }
CPPBRIDGE_EXPORT const char* utf8_unterminated() { static const char text[4]={'a','b','c','d'}; return text; }
CPPBRIDGE_EXPORT void* echo_pointer(void* pointer) { return pointer; }
CPPBRIDGE_EXPORT Link echo_link(Link link) { return link; }
CPPBRIDGE_EXPORT std::int32_t apply(std::int32_t value, std::int32_t(*callback)(std::int32_t)) { return callback(value)+1; }
CPPBRIDGE_EXPORT std::int32_t apply_thread(std::int32_t value, std::int32_t(*callback)(std::int32_t)) {
    std::int32_t result=0; std::thread thread([&]{ result=callback(value); }); thread.join(); return result;
}
CPPBRIDGE_EXPORT Point apply_point(Point point, Point(*callback)(Point)) { return callback(point); }
CPPBRIDGE_EXPORT Color apply_color(Color value, Color(*callback)(Color)) { return callback(value); }
CPPBRIDGE_EXPORT void apply_void(void(*callback)()) { callback(); }
CPPBRIDGE_EXPORT const char* fixture_error() { return cppbridge::last_error(); }
CPPBRIDGE_EXPORT std::int32_t checked_operation(std::int32_t mode) noexcept {
    return cppbridge::guard([&]{ if(mode==1) throw std::runtime_error("bad operation"); if(mode==2) throw 42; });
}
CPPBRIDGE_EXPORT std::int32_t union_int(Number value) { return value.integer; }
CPPBRIDGE_EXPORT Number union_make(std::int32_t value) { Number result{}; result.integer=value; return result; }
CPPBRIDGE_EXPORT double variadic_sum(std::int32_t count, ...) {
    va_list values; va_start(values,count); double result=0;
    for(std::int32_t i=0;i<count;i++) result+=va_arg(values,double);
    va_end(values); return result;
}
