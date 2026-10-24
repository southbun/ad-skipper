#include "../../main/cpp/native_guard.h"
#include <cassert>
#include <stdexcept>
#include <string>

int main() {
    int calls = 0;
    std::string error;
    auto report = [&](const char * message) { ++calls; error = message; };
    assert(guard_native_call<bool>([] { return true; }, report));
    assert(calls == 0);

    auto fail = []() -> const char * {
        throw std::runtime_error("vk::Queue::submit: ErrorDeviceLost");
    };
    assert(guard_native_call<const char *>(fail, report) == nullptr);
    assert(calls == 1 && error == "vk::Queue::submit: ErrorDeviceLost");
    assert(!guard_native_call<bool>([]() -> bool { throw 42; }, report));
    assert(calls == 2 && error == "Unknown native model error");
    assert(guard_native_call<bool>([] { return true; }, report));
    assert(calls == 2);
}
