package com.adskipper.core.detect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @Test fun theSameRunnerCanRecognizeEveryNewForegroundVisit() = runTest {
        val sessions = SplashSessionTracker()
        val released = mutableListOf<String>()
        var calls = 0
        val fallback = VisualFallback<String>(this, { _, _ -> calls++; target }, released::add)
        repeat(10) { visit ->
            val now = visit * 10_000L
            sessions.observeForeground("launcher", false, now)
            val session = sessions.observeForeground("app.a", true, now + 100)!!
            val currentKey = key.copy(sessionId = session.id)
            val image = "frame-$visit"
            assertTrue(fallback.submit(currentKey, image, 4_000))
            runCurrent()
            assertEquals(image, fallback.take(currentKey)?.image)
            sessions.finish(session)
            fallback.clear()
        }
        assertEquals(10, calls)
        assertTrue(released.isEmpty())
    }

    @Test fun aTimedOutAttemptDoesNotDisableLaterAttempts() = runTest {
        val released = mutableListOf<String>()
        val fallback = VisualFallback<String>(this, { image, _ ->
            if (image == "slow") delay(10_000)
            target
        }, released::add)
        fallback.submit(key, "slow", 1_000)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(fallback.submit(key.copy(sessionId = 2), "next", 4_000))
        runCurrent()
        assertEquals("next", fallback.take(key.copy(sessionId = 2))?.image)
        assertEquals(listOf("slow"), released)
    }

    @Test fun lateNativeCompletionAfterCancellationCannotOverwriteANewVisit() = runTest {
        val released = mutableListOf<String>()
        val nativeExit = CompletableDeferred<Unit>()
        val nativeLock = Mutex()
        val fallback = VisualFallback<String>(this, { image, _ ->
            withContext(NonCancellable) {
                nativeLock.withLock {
                    if (image == "old native call") nativeExit.await()
                    target
                }
            }
        }, released::add)
        fallback.submit(key, "old native call", 4_000)
        runCurrent()
        fallback.clear()
        val newKey = key.copy(sessionId = 2)
        assertTrue(fallback.submit(newKey, "new frame", 4_000))
        runCurrent()
        nativeExit.complete(Unit)
        runCurrent()
        assertEquals("new frame", fallback.take(newKey)?.image)
        assertEquals(listOf("old native call"), released)
    }
}
