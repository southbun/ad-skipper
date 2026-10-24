#pragma once

#include <exception>

// C++ exceptions must be reported before returning across the JNI boundary.
template <typename Result, typename Action, typename Report>
Result guard_native_call(Action action, Report report) noexcept {
    try {
        return action();
    } catch (const std::exception & error) {
        report(error.what());
    } catch (...) {
        report("Unknown native model error");
    }
    return Result{};
}
