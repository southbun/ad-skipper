package com.adskipper.core.service

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** Scan immediately; content events wake the bounded fallback poll without queuing work. */
internal suspend fun pollForSkipButton(
    updates: ReceiveChannel<Unit>,
    isCurrent: () -> Boolean,
    detect: suspend () -> Boolean,
): Boolean {
    while (currentCoroutineContext().isActive && isCurrent()) {
        if (detect()) return true
        withTimeoutOrNull(250L) { updates.receive() }
    }
    return false
}
