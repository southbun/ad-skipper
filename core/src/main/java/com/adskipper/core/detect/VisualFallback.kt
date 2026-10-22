package com.adskipper.core.detect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Owned frames stay with the background model until inference really finishes. Call on the service thread. */
class VisualFallback<I>(
    private val scope: CoroutineScope,
    private val detect: suspend (I, Long) -> SkipTarget?,
    private val release: (I) -> Unit,
) {
    data class Key(val sessionId: Long, val windowId: Int, val settingsRevision: Long)
    data class Proposal<I>(val key: Key, val image: I, val target: SkipTarget)
    private var job: Job? = null
    private var generation = 0L
    private var pending: Proposal<I>? = null

    /** True transfers ownership of image; false leaves it with the caller. */
    fun submit(key: Key, image: I, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0 || job?.isActive == true || pending != null) return false
        val expected = ++generation
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var transferred = false
            try {
                val target = withTimeoutOrNull(timeoutMs) { detect(image, timeoutMs) }
                currentCoroutineContext().ensureActive()
                if (target != null && generation == expected) {
                    pending = Proposal(key, image, target)
                    transferred = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Background visual fallback failed")
            } finally {
                if (!transferred) release(image)
            }
        }
        return true
    }

    /** The consumer owns a returned frame and must release it after fresh-frame verification. */
    fun take(key: Key): Proposal<I>? {
        val proposal = pending ?: return null
        pending = null
        if (proposal.key != key) {
            release(proposal.image)
            return null
        }
        return proposal
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        pending?.image?.let(release)
        pending = null
    }
}
