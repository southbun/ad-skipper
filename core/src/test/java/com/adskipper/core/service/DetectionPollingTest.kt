package com.adskipper.core.service

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetectionPollingTest {
    @Test fun firstScanRunsImmediatelyAndNeedsNoFixedStartupDelay() = runTest {
        var detectedAt = -1L
        assertTrue(pollForSkipButton(Channel(Channel.CONFLATED), { true }) {
            detectedAt = testScheduler.currentTime
            true
        })
        assertEquals(0L, detectedAt)
    }

    @Test fun aContentChangeWakesDetectionBeforeTheFallbackInterval() = runTest {
        val updates = Channel<Unit>(Channel.CONFLATED)
        val attempts = mutableListOf<Long>()
        val job = launch {
            pollForSkipButton(updates, { true }) {
                attempts += testScheduler.currentTime
                attempts.size == 2
            }
        }
        runCurrent()
        advanceTimeBy(50)
        updates.trySend(Unit)
        runCurrent()
        assertEquals(listOf(0L, 50L), attempts)
        assertTrue(job.isCompleted)
    }

    @Test fun silentScreensStillPollAndNeverScanAfterTheSessionExpires() = runTest {
        val attempts = mutableListOf<Long>()
        val found = pollForSkipButton(Channel(Channel.CONFLATED), { testScheduler.currentTime < 8_000 }) {
            attempts += testScheduler.currentTime
            false
        }
        assertFalse(found)
        assertEquals(32, attempts.size)
        assertEquals(250L, attempts[1] - attempts[0])
        assertEquals(7_750L, attempts.last())
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test fun cancellingTheSessionStopsAllRetries() = runTest {
        var attempts = 0
        val job = launch {
            pollForSkipButton(Channel(Channel.CONFLATED), { true }) { attempts++; false }
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(1_000)
        assertEquals(1, attempts)
    }
}
