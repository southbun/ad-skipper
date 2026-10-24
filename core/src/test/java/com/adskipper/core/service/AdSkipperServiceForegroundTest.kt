package com.adskipper.core.service

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adskipper.core.data.AppSettings
import com.adskipper.core.detect.SplashSessionTracker
import com.adskipper.core.learn.LearningController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class AdSkipperServiceForegroundTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: AdSkipperService
    private lateinit var sessions: SplashSessionTracker
    private val app = "test.ad.app"
    private val home = "com.miui.home"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        service = Robolectric.buildService(AdSkipperService::class.java).create().get()
        ReflectionHelpers.setField(service, "settings", AppSettings(enabledPackages = setOf(app)))
        ReflectionHelpers.setField(service, "overlay", DebugOverlay(service))
        sessions = ReflectionHelpers.getField(service, "sessions")
    }

    @After fun cleanup() {
        LearningController.discard()
        ReflectionHelpers.getField<CoroutineScope>(service, "scope").cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Suppress("DEPRECATION")
    private fun send(rootPackage: String?, eventPackage: String?, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                     className: String? = null) {
        val root = rootPackage?.let { pkg -> AccessibilityNodeInfo.obtain().apply { packageName = pkg } }
        shadowOf(service).setRootInActiveWindow(root)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply {
            packageName = eventPackage
            this.className = className
        })
    }

    private fun current() = sessions.observeForeground(app, true, SystemClock.elapsedRealtime())

    @Suppress("DEPRECATION")
    private fun installBrowserFixtures() {
        fun activity(pkg: String) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.BrowserActivity" }
        }
        val manager = shadowOf(service.packageManager)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.invalid/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        for (pkg in listOf("com.taobao.taobao", "com.sina.weibo", "org.example.browser")) {
            manager.addResolveInfoForIntent(web, activity(pkg))
        }
        manager.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), activity("org.example.browser"))
        manager.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), activity("org.example.home"))
    }

    @Test fun embeddedWebActivitiesDoNotExcludeTheirWholeApp() {
        installBrowserFixtures()
        val excluded = AdSkipperService.protectedPackages(service)
        assertFalse("Taobao's embedded browser must not disable splash detection", "com.taobao.taobao" in excluded)
        assertFalse("Weibo's embedded browser must not disable splash detection", "com.sina.weibo" in excluded)
        assertTrue("Unlisted browser applications must still be excluded", "org.example.browser" in excluded)
        assertTrue("Custom launchers must still be excluded", "org.example.home" in excluded)
        assertTrue("Known browsers must remain excluded", "com.android.chrome" in excluded)
    }

    @Test fun aWeiboForegroundEventStartsDetectionWithTheResolvedExclusions() {
        installBrowserFixtures()
        ReflectionHelpers.setField(service, "excludedPackages", AdSkipperService.protectedPackages(service))
        ReflectionHelpers.setField(service, "settings", AppSettings(enabledPackages = setOf("com.sina.weibo")))
        send("com.sina.weibo", "com.sina.weibo")
        assertNotNull("A normal app with a web activity must enter the detection loop",
            ReflectionHelpers.getField<Long?>(service, "pollingId"))
    }

    @Test fun defaultsNeverStartRecognitionForAnyExternalApp() {
        ReflectionHelpers.setField(service, "settings", AppSettings())
        for (pkg in listOf(app, "com.sina.weibo", "com.taobao.taobao")) {
            send(pkg, pkg)
            send(pkg, pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            assertNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
            assertNull(ReflectionHelpers.getField<Job?>(service, "pollJob"))
        }
    }

    @Test fun onlySelectedAppsCanStartRecognition() {
        send("unselected.app", "unselected.app")
        assertNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
        send(app, app)
        assertNotNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
    }

    @Test fun collectingASamplePausesAutomaticClicksInThatApp() {
        LearningController.arm(app)
        send(app, app)
        assertNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
        LearningController.discard()
        send(app, app)
        assertNotNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
    }

    @Test fun learningDoesNotPauseUnrelatedSelectedApps() {
        LearningController.arm("another.app")
        send(app, app)
        assertNotNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
    }

    @Test fun leavingForAnUnreadableUnselectedAppEndsTheSession() {
        send(app, app)
        val first = current()!!
        val job = ReflectionHelpers.getField<Job>(service, "pollJob")
        send(null, "unselected.app")
        assertFalse(sessions.isCurrent(first, SystemClock.elapsedRealtime()))
        assertTrue(job.isCancelled)
    }

    @Test fun leavingForAnUnselectedAppCancelsPendingAutomaticLearning() {
        send(app, app)
        val learning = Job()
        ReflectionHelpers.setField(service, "autoLearningJob", learning)
        send("another.app", "another.app")
        assertTrue(learning.isCancelled)
    }

    @Test fun disablingTheCurrentAppCancelsPendingAutomaticLearning() {
        send(app, app)
        val learning = Job()
        ReflectionHelpers.setField(service, "autoLearningJob", learning)
        ReflectionHelpers.setField(service, "settings", AppSettings())
        send(app, app)
        assertTrue(learning.isCancelled)
    }

    @Test fun disablingTheCurrentAppStopsFurtherRecognition() {
        send(app, app)
        val job = ReflectionHelpers.getField<Job>(service, "pollJob")
        ReflectionHelpers.setField(service, "settings", AppSettings())
        send(app, app)
        assertTrue(job.isCancelled)
        assertNull(ReflectionHelpers.getField<Any?>(sessions, "active"))
    }

    @Test fun protectedAppsCannotBeEnabledByAStoredSelection() {
        ReflectionHelpers.setField(service, "excludedPackages", AdSkipperService.protectedPackages(service))
        ReflectionHelpers.setField(service, "settings", AppSettings(enabledPackages = setOf("com.android.chrome", home)))
        send("com.android.chrome", "com.android.chrome")
        assertNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
        send(home, home)
        assertNull(ReflectionHelpers.getField<Long?>(service, "pollingId"))
    }

    @Test fun selectedContentUpdatesWakeTheSameSession() {
        send(app, app)
        val first = current()!!
        val updates = ReflectionHelpers.getField<Channel<Unit>>(service, "pollUpdates")
        assertTrue(updates.tryReceive().isFailure)
        send(app, app, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertTrue(updates.tryReceive().isSuccess)
        assertEquals(first.id, ReflectionHelpers.getField<Long?>(service, "pollingId")!!)
    }

    @Test fun unrelatedContentUpdatesDoNotWakeRecognition() {
        send(app, app)
        val updates = ReflectionHelpers.getField<Channel<Unit>>(service, "pollUpdates")
        send(app, "unselected.app", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertTrue(updates.tryReceive().isFailure)
    }

    @Test fun connectionStateTracksTheActualServiceLifecycle() {
        assertFalse(AdSkipperService.connected.value)
        shadowOf(service.application).grantPermissions(
            "${service.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        try {
            ReflectionHelpers.callInstanceMethod<Any?>(service, "onServiceConnected")
            assertTrue(AdSkipperService.connected.value)
            service.onUnbind(Intent())
            assertFalse(AdSkipperService.connected.value)
        } finally {
            service.onDestroy()
        }
        assertFalse(AdSkipperService.connected.value)
    }

    @Test fun appCanRunAgainAfterLauncherEventWithNoReadableRoot() {
        send(app, app)
        val first = current()!!
        sessions.finish(first)
        send(null, home)
        send(app, app)
        val second = current()
        assertNotNull("Returning from the launcher must create a fresh recognition session", second)
        assertNotEquals(first.id, second!!.id)
        assertEquals(second.id, ReflectionHelpers.getField<Long?>(service, "pollingId")!!)
    }

    @Test fun temporarilyUnreadableContentDoesNotPermanentlyDisableTheCurrentSession() {
        send(app, app)
        val first = current()!!
        send(null, app, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        send(app, app, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertEquals(first, current())
    }

    @Test fun readableLauncherStillAllowsAnotherVisit() {
        send(app, app)
        val first = current()!!
        sessions.finish(first)
        send(home, home)
        send(app, app)
        assertNotEquals(first.id, current()!!.id)
    }

    @Test fun contentEventsWithoutARootCannotRenewAConsumedSession() {
        send(app, app)
        sessions.finish(current()!!)
        send(null, home, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        send(app, app)
        assertNull(current())
    }

    @Test fun anOverlayStateEventWithNoRootDoesNotCancelRecognition() {
        send(app, app)
        val first = current()!!
        send(null, service.packageName)
        assertEquals(first, current())
    }

    @Test fun anOverlayWindowDoesNotDisableTheBuiltInAdTest() {
        ReflectionHelpers.setField(service, "settings", AppSettings(selfTest = true))
        val ownPackage = service.packageName
        send(ownPackage, ownPackage, className = "com.adskipper.ui.TestAdActivity")
        val first = sessions.observeForeground(ownPackage, true, SystemClock.elapsedRealtime())!!
        send(ownPackage, ownPackage, className = "android.widget.TextView")
        assertTrue("The status overlay must not end the ad-test recognition window",
            sessions.isCurrent(first, SystemClock.elapsedRealtime()))
    }

    @Test fun leavingTheAdTestForTheMainActivityStillEndsRecognition() {
        ReflectionHelpers.setField(service, "settings", AppSettings(selfTest = true))
        val ownPackage = service.packageName
        send(ownPackage, ownPackage, className = "com.adskipper.ui.TestAdActivity")
        val first = sessions.observeForeground(ownPackage, true, SystemClock.elapsedRealtime())!!
        send(ownPackage, ownPackage, className = "com.adskipper.ui.MainActivity")
        assertFalse(sessions.isCurrent(first, SystemClock.elapsedRealtime()))
        assertNull(ReflectionHelpers.getField<Any?>(sessions, "active"))
    }
}
