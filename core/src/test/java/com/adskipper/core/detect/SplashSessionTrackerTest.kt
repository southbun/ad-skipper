package com.adskipper.core.detect

import org.junit.Assert.*
import org.junit.Test

class SplashSessionTrackerTest {
    @Test fun contentChangesNeverExtendEightSeconds() {
        val tracker = SplashSessionTracker()
        val session = tracker.observeForeground("app.a", true, 100)!!
        assertEquals(session, tracker.observeForeground("app.a", true, 7_900))
        assertNull(tracker.observeForeground("app.a", true, 8_100))
        assertNull(tracker.observeForeground("app.a", true, 30_000))
    }

    @Test fun foregroundSwitchInvalidatesInFlightResult() {
        val tracker = SplashSessionTracker()
        val first = tracker.observeForeground("app.a", true, 100)!!
        tracker.observeForeground("settings", false, 200)
        assertFalse(tracker.isCurrent(first, 201))
        val second = tracker.observeForeground("app.a", true, 300)!!
        assertNotEquals(first.id, second.id)
        assertFalse(tracker.isCurrent(first, 301))
        assertTrue(tracker.isCurrent(second, 301))
    }

    @Test fun oneAttemptConsumesWholeSession() {
        val tracker = SplashSessionTracker()
        val session = tracker.observeForeground("app.a", true, 0)!!
        tracker.finish(session)
        assertFalse(tracker.isCurrent(session, 1))
        assertNull(tracker.observeForeground("app.a", true, 500))
    }

    @Test fun switchOffInvalidatesPendingClick() {
        val tracker = SplashSessionTracker()
        val session = tracker.observeForeground("app.a", true, 100)!!
        tracker.invalidate()
        assertFalse(tracker.isCurrent(session, 200))
        assertEquals(0L, tracker.remainingMs(session, 200))
        assertNull(tracker.observeForeground("app.a", true, 300))
    }

    @Test fun oldJobCannotFinishNewSession() {
        val tracker = SplashSessionTracker()
        val old = tracker.observeForeground("app.a", true, 0)!!
        val current = tracker.observeForeground("app.b", true, 50)!!
        tracker.finish(old)
        assertTrue(tracker.isCurrent(current, 100))
    }

    @Test fun excludedAppsAndClockRewindCannotAuthorizeClicks() {
        val tracker = SplashSessionTracker()
        assertNull(tracker.observeForeground("settings", false, 0))
        val current = tracker.observeForeground("app.a", true, 100)!!
        assertFalse(tracker.isCurrent(current, 99))
        assertEquals(7_999L, tracker.remainingMs(current, 101))
    }
}
