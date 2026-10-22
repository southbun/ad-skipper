package com.adskipper.core.detect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VisualFallbackTest {
    private val key = VisualFallback.Key(1, 2, 3)
    private val target = SkipTarget(950f, 100f, SkipLayer.L3_VLM)

    @Test fun slowModelDoesNotBlockFastDetectionOfALaterAppearingButton() = runTest {
        val released = mutableListOf<String>()
        val modelResult = CompletableDeferred<SkipTarget?>()
        val fallback = VisualFallback<String>(this, { _, _ -> modelResult.await() }, released::add)
        assertTrue(fallback.submit(key, "old branding frame", 4_000))
        var fastHit = false
        launch { delay(500); fastHit = true }
        advanceTimeBy(501)
        runCurrent()
        assertTrue(fastHit)
        assertNull(fallback.take(key))
        fallback.clear()
        runCurrent()
        assertEquals(listOf("old branding frame"), released)
    }

    @Test fun completedProposalTransfersFrameOwnershipToItsConsumer() = runTest {
        val released = mutableListOf<String>()
        val fallback = VisualFallback<String>(this, { _, _ -> target }, released::add)
        assertTrue(fallback.submit(key, "frame", 4_000))
        runCurrent()
        val proposal = fallback.take(key)
        assertEquals(target, proposal?.target)
        assertTrue(released.isEmpty())
        fallback.clear()
        assertTrue(released.isEmpty())
        released += proposal!!.image
        assertEquals(listOf("frame"), released)
    }

    @Test fun clearingImmediatelyStillReleasesTheOwnedFrameExactlyOnce() = runTest {
        val released = mutableListOf<String>()
        val gate = CompletableDeferred<SkipTarget?>()
        val fallback = VisualFallback<String>(this, { _, _ -> gate.await() }, released::add)
        fallback.submit(key, "cancelled", 4_000)
        fallback.clear()
        runCurrent()
        gate.complete(target)
        runCurrent()
        fallback.clear()
        assertNull(fallback.take(key))
        assertEquals(listOf("cancelled"), released)
    }

    @Test fun windowSessionOrSettingsChangesRejectPendingResults() = runTest {
        for (other in listOf(key.copy(sessionId = 4), key.copy(windowId = 4), key.copy(settingsRevision = 4))) {
            val released = mutableListOf<String>()
            val fallback = VisualFallback<String>(this, { _, _ -> target }, released::add)
            fallback.submit(key, "old", 4_000)
            runCurrent()
            assertNull(fallback.take(other))
            assertEquals(listOf("old"), released)
        }
    }

    @Test fun onlyOneInferenceOrUnconsumedProposalIsKept() = runTest {
        val released = mutableListOf<String>()
        val gate = CompletableDeferred<SkipTarget?>()
        val fallback = VisualFallback<String>(this, { _, _ -> gate.await() }, released::add)
        assertTrue(fallback.submit(key, "first", 4_000))
        assertFalse(fallback.submit(key, "second", 4_000))
        gate.complete(target)
        runCurrent()
        assertFalse(fallback.submit(key, "third", 4_000))
        fallback.clear()
        assertEquals(listOf("first"), released)
    }

    @Test fun timeoutsAndModelFailuresReleaseTheirFrames() = runTest {
        val released = mutableListOf<String>()
        val slow = VisualFallback<String>(this, { _, _ -> delay(10_000); target }, released::add)
        slow.submit(key, "timeout", 1_000)
        advanceTimeBy(1_001)
        runCurrent()
        assertNull(slow.take(key))
        val failed = VisualFallback<String>(this, { _, _ -> error("model unavailable") }, released::add)
        failed.submit(key, "failed", 1_000)
        runCurrent()
        assertNull(failed.take(key))
        assertEquals(listOf("timeout", "failed"), released)
    }
}
