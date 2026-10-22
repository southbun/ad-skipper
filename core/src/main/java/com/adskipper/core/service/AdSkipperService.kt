package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.adskipper.core.data.AppSettings
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.detect.DetectionPipeline
import com.adskipper.core.detect.NodeMatcher
import com.adskipper.core.detect.SkipSafety
import com.adskipper.core.detect.SkipTarget
import com.adskipper.core.detect.SplashSessionTracker
import com.adskipper.core.detect.YoloSkipDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AdSkipperService : AccessibilityService() {
    // Serialize session, switch and foreground changes on the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = SplashSessionTracker()
    private var settings = AppSettings(masterEnabled = false)
    private var settingsRevision = 0L
    private var pollJob: Job? = null
    private var pollingId: Long? = null
    private lateinit var stats: StatsRepository
    private lateinit var overlay: DebugOverlay
    private var yolo: YoloSkipDetector? = null
    private var excludedPackages = emptySet<String>()
    private var selfLabels = emptySet<String>()
    private var selfTestVisible = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopSession()
        }
    }

    override fun onServiceConnected() {
        stats = StatsRepository(this)
        overlay = DebugOverlay(this)
        excludedPackages = resolveExcludedPackages()
        selfLabels = setOf(applicationInfo.loadLabel(packageManager).toString())
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startNotification()
        scope.launch {
            SettingsRepository(this@AdSkipperService).settings.collect { new ->
                if (new != settings) {
                    settingsRevision++
                    stopSession()
                    overlay.dismiss()
                }
                settings = new
                if (new.masterEnabled && new.layer3Enabled && yolo == null) {
                    yolo = YoloSkipDetector(this@AdSkipperService).takeIf { it.isReady }
                    launch {
                        delay(8_000L)
                        if (settings.masterEnabled && settings.layer3Enabled) {
                            withContext(Dispatchers.Default) { yolo?.warmUp() }
                        }
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        val root = rootInActiveWindow ?: run { stopSession(); return }
        val pkg = root.packageName?.toString() ?: run { stopSession(); return }
        if (pkg == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            selfTestVisible = event.className?.toString()?.endsWith("TestAdActivity") == true
        }
        val allowed = settings.masterEnabled &&
            (settings.layer1Enabled || settings.layer2Enabled || settings.layer3Enabled) &&
            pkg !in settings.whitelist && pkg !in excludedPackages &&
            (pkg != packageName || (settings.selfTest && selfTestVisible)) &&
            getSystemService(PowerManager::class.java).isInteractive
        val session = sessions.observeForeground(pkg, allowed, SystemClock.elapsedRealtime())
        if (session == null) {
            if (!allowed) pollJob?.cancel()
            return
        }
        if (pollingId == session.id) return
        pollJob?.cancel()
        pollingId = session.id
        pollJob = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adskipper:splash")
            try {
                wakeLock.acquire(8_000L)
                delay(500)
                while (isActive && sessions.isCurrent(session, SystemClock.elapsedRealtime())) {
                    if (detectAndClick(session)) break
                    delay(750)
                }
            } finally {
                sessions.finish(session)
                if (wakeLock.isHeld) wakeLock.release()
                if (pollingId == session.id) pollingId = null
            }
        }
    }

    private fun stopSession() {
        sessions.invalidate()
        pollJob?.cancel()
        pollJob = null
        pollingId = null
    }

    private fun validRoot(session: SplashSessionTracker.Session, windowId: Int? = null): AccessibilityNodeInfo? {
        if (!settings.masterEnabled || !sessions.isCurrent(session, SystemClock.elapsedRealtime())) return null
        if (session.packageName in settings.whitelist || session.packageName in excludedPackages) return null
        if (!getSystemService(PowerManager::class.java).isInteractive) return null
        return rootInActiveWindow?.takeIf {
            if (windowId == null) it.packageName?.toString() == session.packageName
            else SkipSafety.sameWindow(session.packageName, windowId, it.packageName?.toString(), it.windowId)
        }
    }

    private suspend fun detectAndClick(session: SplashSessionTracker.Session): Boolean {
        val root = validRoot(session) ?: return false
        val windowId = root.windowId
        val revision = settingsRevision
        val snapshot = settings
        val pipeline = DetectionPipeline.create(if (snapshot.layer3Enabled) yolo else null, selfLabels)
        val started = SystemClock.elapsedRealtime()
        suspend fun pass(node: AccessibilityNodeInfo): SkipTarget? {
            val remaining = sessions.remainingMs(session, SystemClock.elapsedRealtime())
            if (remaining <= 0) return null
            val evidence = NodeMatcher.hasAdEvidence(node)
            return withTimeoutOrNull(remaining) {
                withContext(Dispatchers.Default) {
                    pipeline.detect(
                        node,
                        { ScreenshotCapturer.capture(this@AdSkipperService) },
                        snapshot,
                        nodeAdEvidence = evidence,
                    ).target
                }
            }
        }
        return try {
            val first = pass(root) ?: return false
            currentCoroutineContext().ensureActive()
            if (revision != settingsRevision || validRoot(session, windowId) == null) return false
            delay(120)
            val freshRoot = validRoot(session, windowId) ?: return false
            if (!freshRoot.refresh()) return false
            val verified = pass(freshRoot) ?: return false
            currentCoroutineContext().ensureActive()
            if (!SkipSafety.sameTarget(first, verified) || revision != settingsRevision ||
                validRoot(session, windowId) == null) return false

            // Consume before dispatch: one attempt, even if the gesture is cancelled.
            sessions.finish(session)
            val completed = performClick(verified.x, verified.y)
            if (completed) {
                stats.record(session.packageName, verified.layer.name, SystemClock.elapsedRealtime() - started)
                if (settings.debugOverlay) overlay.update("已完成点击：${verified.layer}")
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun performClick(x: Float, y: Float): Boolean = withTimeoutOrNull(1_000L) {
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 50),
            ).build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            if (!dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
        }
    } ?: false

    @Suppress("DEPRECATION")
    private fun resolveExcludedPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val browser = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://example.invalid/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val dynamic = listOf(home, browser).flatMap { intent ->
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        }
        return dynamic.toSet() + setOf(
            "com.android.quicksearchbox", "com.google.android.googlequicksearchbox", "com.miui.personalassistant",
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.mozilla.firefox",
            "com.microsoft.emmx", "com.opera.browser", "com.brave.browser", "com.android.browser",
            "com.mi.globalbrowser", "com.heytap.browser", "com.coloros.browser", "com.vivo.browser",
            "com.huawei.browser", "com.sec.android.app.sbrowser", "com.tencent.mtt", "com.baidu.browser.apps",
            "com.UCMobile", "com.ucmobile.lite", "com.quark.browser",
        )
    }

    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("keepalive", "运行状态", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, "keepalive")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("开屏跳过运行中").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onInterrupt() { stopSession() }

    override fun onDestroy() {
        stopSession()
        if (::overlay.isInitialized) overlay.dismiss()
        runCatching { unregisterReceiver(screenReceiver) }
        yolo?.close()
        scope.cancel()
        super.onDestroy()
    }
}
