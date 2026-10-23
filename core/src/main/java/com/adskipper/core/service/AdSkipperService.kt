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
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.adskipper.core.data.AppSettings
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.detect.DetectionPipeline
import com.adskipper.core.detect.FrameVerifier
import com.adskipper.core.detect.VlmDetector
import com.adskipper.core.detect.VisualFallback
import com.adskipper.core.model.ModelManager
import com.adskipper.core.vlm.VlmEngine
import com.adskipper.core.detect.NodeMatcher
import com.adskipper.core.detect.SkipLayer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AdSkipperService : AccessibilityService() {
    companion object {
        private val mutableConnected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = mutableConnected
    }
    // Serialize session, switch and foreground changes on the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = SplashSessionTracker()
    private var settings = AppSettings(masterEnabled = false)
    private var settingsRevision = 0L
    private var pollJob: Job? = null
    private var feedbackJob: Job? = null
    private var modelJob: Job? = null
    private var pollingId: Long? = null
    private lateinit var stats: StatsRepository
    private lateinit var overlay: DebugOverlay
    private lateinit var visualFallback: VisualFallback<Bitmap>
    private var yolo: YoloSkipDetector? = null
    private var excludedPackages = emptySet<String>()
    private var selfLabels = emptySet<String>()
    private var selfTestVisible = false
    private var lastMissReason = "未找到符合条件的跳过按钮"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopSession()
        }
    }

    override fun onServiceConnected() {
        stats = StatsRepository(this)
        overlay = DebugOverlay(this)
        visualFallback = VisualFallback(
            scope,
            detect = { image, timeout ->
                withContext(Dispatchers.Default) {
                    DetectionPipeline.create(null, VlmDetector(), selfLabels).detect(
                        null, { image },
                        AppSettings(layer1Enabled = false, layer2Enabled = false, layer3Enabled = false, vlmEnabled = true),
                        timeBudgetMs = timeout,
                    ).target
                }
            },
            release = { it.recycle() },
        )
        excludedPackages = resolveExcludedPackages()
        selfLabels = setOf(applicationInfo.loadLabel(packageManager).toString())
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startNotification()
        mutableConnected.value = true
        scope.launch {
            SettingsRepository(this@AdSkipperService).settings.collect { new ->
                if (new != settings) {
                    settingsRevision++
                    stopSession()
                    overlay.dismiss()
                }
                settings = new
                modelJob?.cancel()
                modelJob = launch(Dispatchers.IO) {
                    if (new.masterEnabled && new.vlmEnabled) {
                        ModelManager(this@AdSkipperService).loadModel()
                    } else {
                        VlmEngine.release()
                    }
                }
                if (new.masterEnabled && new.layer3Enabled && yolo == null) {
                    yolo = YoloSkipDetector(this@AdSkipperService).takeIf { it.isReady }
                    launch {
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
        // A temporarily unreadable tree is not a foreground switch. Keep the original budget.
        val pkg = rootInActiveWindow?.packageName?.toString()
            ?: event.packageName?.toString()?.takeIf {
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    (it in settings.whitelist || it in excludedPackages)
            } ?: return
        val activityClass = event.className?.toString()
        if (pkg == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            activityClass?.endsWith("Activity") == true) {
            selfTestVisible = activityClass.endsWith("TestAdActivity")
        }
        val allowed = settings.masterEnabled &&
            (settings.layer1Enabled || settings.layer2Enabled || settings.layer3Enabled || settings.vlmEnabled) &&
            pkg !in settings.whitelist && pkg !in excludedPackages &&
            (pkg != packageName || (settings.selfTest && selfTestVisible)) &&
            getSystemService(PowerManager::class.java).isInteractive
        val session = sessions.observeForeground(pkg, allowed, SystemClock.elapsedRealtime())
        if (session == null) {
            if (!allowed) pollJob?.cancel()
            return
        }
        if (pollingId == session.id) return
        clearVisualFallback()
        pollJob?.cancel()
        feedbackJob?.cancel()
        overlay.dismiss()
        pollingId = session.id
        lastMissReason = "未找到符合条件的跳过按钮"
        pollJob = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adskipper:splash")
            val initialVlmCount = VlmEngine.inferenceStatus.value.count
            try {
                wakeLock.acquire(8_000L)
                if (settings.debugOverlay) overlay.update("正在识别开屏")
                delay(500)
                var attempted = false
                while (isActive && sessions.isCurrent(session, SystemClock.elapsedRealtime())) {
                    if (detectAndClick(session)) {
                        attempted = true
                        break
                    }
                    delay(750)
                }
                if (!attempted && isActive && settings.debugOverlay &&
                    SystemClock.elapsedRealtime() - session.startedAt >= 8_000L) {
                    val inference = VlmEngine.inferenceStatus.value
                    overlay.update(if (inference.count > initialVlmCount) {
                        "大模型：${inference.message}" +
                            if (inference.elapsedMs > 0) "（${inference.elapsedMs} ms）" else ""
                    } else lastMissReason)
                }
            } finally {
                sessions.finish(session)
                if (wakeLock.isHeld) wakeLock.release()
                if (pollingId == session.id) pollingId = null
                if (pollingId == null) clearVisualFallback()
            }
        }
    }

    private fun stopSession() {
        clearVisualFallback()
        sessions.invalidate()
        pollJob?.cancel()
        pollJob = null
        pollingId = null
        feedbackJob?.cancel()
        feedbackJob = null
    }

    private fun clearVisualFallback() {
        VlmEngine.cancelInference()
        if (::visualFallback.isInitialized) visualFallback.clear()
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
        val pipeline = DetectionPipeline.create(if (snapshot.layer3Enabled) yolo else null, null, selfLabels)
        val frames = ArrayList<Bitmap>()
        val started = SystemClock.elapsedRealtime()
        suspend fun pass(node: AccessibilityNodeInfo): SkipTarget? {
            val remaining = sessions.remainingMs(session, SystemClock.elapsedRealtime())
            if (remaining <= 0) return null
            val evidence = NodeMatcher.hasAdEvidence(node)
            val result = withTimeoutOrNull(remaining) {
                withContext(Dispatchers.Default) {
                    pipeline.detect(
                        node,
                        { ScreenshotCapturer.capture(this@AdSkipperService)?.also(frames::add) },
                        snapshot.copy(vlmEnabled = false),
                        nodeAdEvidence = evidence,
                        timeBudgetMs = remaining,
                    )
                }
            }
            if (sessions.isCurrent(session, SystemClock.elapsedRealtime()) && result?.target == null) {
                lastMissReason = result?.missReason ?: "画面识别超时"
            }
            return result?.target
        }
        return try {
            val key = VisualFallback.Key(session.id, windowId, revision)
            val proposal = visualFallback.take(key)
            proposal?.image?.let(frames::add)
            // A ready model proposal goes straight to fresh-frame verification.
            val fast = if (proposal == null) pass(root) else null
            val first = fast ?: proposal?.target ?: run {
                if (snapshot.vlmEnabled && VlmEngine.isReady && validRoot(session, windowId) != null) {
                    val image = frames.lastOrNull() ?: ScreenshotCapturer.capture(this@AdSkipperService)?.also(frames::add)
                    if (image == null) lastMissReason = "截图失败，暂未启动大模型"
                    if (image != null && visualFallback.submit(key, image,
                            minOf(4_000L, sessions.remainingMs(session, SystemClock.elapsedRealtime())))) {
                        frames.remove(image)
                        if (settings.debugOverlay) overlay.update("已提交大模型识别")
                    }
                }
                return false
            }
            val sampledAt = SystemClock.elapsedRealtime()
            currentCoroutineContext().ensureActive()
            if (revision != settingsRevision || validRoot(session, windowId) == null) return false
            if (first.requiresCountdownProof && settings.debugOverlay) overlay.update("正在确认跳过倒计时")
            delay(if (first.requiresCountdownProof) 1_050 else 120)
            val freshRoot = validRoot(session, windowId) ?: return false
            if (!freshRoot.refresh()) return false
            val verified = if (first.layer == SkipLayer.L3_VLM) {
                val source = frames.lastOrNull() ?: return false
                val fresh = ScreenshotCapturer.capture(this@AdSkipperService)?.also(frames::add) ?: return false
                if (!FrameVerifier.matches(source, fresh, first)) {
                    lastMissReason = "画面已变化，已放弃旧按钮坐标"
                    return false
                }
                first
            } else pass(freshRoot) ?: return false
            currentCoroutineContext().ensureActive()
            val liveRoot = validRoot(session, windowId) ?: return false
            if (!SkipSafety.canConfirmTarget(first, verified, SystemClock.elapsedRealtime() - sampledAt)) {
                lastMissReason = "按钮或倒计时未通过复查"
                return false
            }
            if (revision != settingsRevision) return false

            val node = if (verified.layer == SkipLayer.L1_NODE) {
                NodeMatcher.findSkipNodeInfo(liveRoot, snapshot.keywords, selfLabels)?.takeIf {
                    if (!it.refresh() || !it.isEnabled || !it.isVisibleToUser ||
                        !SkipSafety.sameWindow(session.packageName, windowId, it.packageName?.toString(), it.windowId)) {
                        false
                    } else {
                        val box = Rect().also(it::getBoundsInScreen)
                        SkipSafety.sameTarget(verified, verified.copy(x = box.exactCenterX(), y = box.exactCenterY()))
                    }
                } ?: return false
            } else null
            if (revision != settingsRevision || validRoot(session, windowId) == null) return false

            // Consume before dispatch; fall back only when the node action was rejected.
            sessions.finish(session)
            clearVisualFallback()
            val viaNode = node?.isClickable == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val completed = viaNode || run {
                currentCoroutineContext().ensureActive()
                val current = rootInActiveWindow
                if (revision != settingsRevision || !settings.masterEnabled ||
                    SystemClock.elapsedRealtime() - session.startedAt >= 8_000L ||
                    !getSystemService(PowerManager::class.java).isInteractive || current == null ||
                    !SkipSafety.sameWindow(session.packageName, windowId, current.packageName?.toString(), current.windowId)) {
                    return true
                }
                performClick(verified.x, verified.y)
            }
            if (completed) {
                showClickFeedback(session, windowId, revision, verified, viaNode)
                stats.record(session.packageName, verified.layer.name, SystemClock.elapsedRealtime() - started)
            } else if (settings.debugOverlay) {
                overlay.update("点击未执行：${verified.layer}")
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun showClickFeedback(session: SplashSessionTracker.Session, windowId: Int, revision: Long,
                                 target: SkipTarget, viaNode: Boolean) {
        if (!settings.debugOverlay) return
        overlay.update("已发送${if (viaNode) "控件" else "触摸"}点击：${target.layer}")
        if (target.layer != SkipLayer.L1_NODE) return
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            delay(700)
            if (revision != settingsRevision || !settings.debugOverlay ||
                !getSystemService(PowerManager::class.java).isInteractive) return@launch
            val message = try {
                val after = rootInActiveWindow
                if (after == null || !after.refresh()) {
                    "点击已发送，页面状态待确认"
                } else if (!SkipSafety.sameWindow(session.packageName, windowId, after.packageName?.toString(), after.windowId)) {
                    "点击后页面已切换"
                } else if (NodeMatcher.findSkipNode(after, settings.keywords, selfLabels) != null) {
                    "按钮仍在，未确认跳过"
                } else {
                    "未再识别到跳过按钮"
                }
            } catch (_: Exception) {
                "点击已发送，页面状态待确认"
            }
            overlay.update(message)
        }
    }

    private suspend fun performClick(x: Float, y: Float): Boolean = withTimeoutOrNull(1_000L) {
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, ViewConfiguration.getTapTimeout().toLong()),
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
        // Web-link handlers also include embedded browsers in normal apps.
        val browser = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
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

    override fun onUnbind(intent: Intent?): Boolean {
        mutableConnected.value = false
        stopSession()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mutableConnected.value = false
        stopSession()
        if (::overlay.isInitialized) overlay.dismiss()
        runCatching { unregisterReceiver(screenReceiver) }
        yolo?.close()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { VlmEngine.release() }
        super.onDestroy()
    }
}
