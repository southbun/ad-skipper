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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.adskipper.core.data.AppSettings
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.detect.DetectionPipeline
import com.adskipper.core.detect.FrameVerifier
import com.adskipper.core.detect.OcrDetector
import com.adskipper.core.detect.OcrLine
import com.adskipper.core.detect.VlmDetector
import com.adskipper.core.detect.VisualFallback
import com.adskipper.core.model.ModelManager
import com.adskipper.core.learn.LearnedMatcher
import com.adskipper.core.learn.AutomaticLearning
import com.adskipper.core.learn.DemonstrationLearning
import com.adskipper.core.learn.DemonstrationOverlay
import com.adskipper.core.learn.DemonstrationTouch
import com.adskipper.core.learn.LearnedNode
import com.adskipper.core.learn.LearnedRuleRepository
import com.adskipper.core.learn.LearningController
import com.adskipper.core.learn.LearningOverlay
import com.adskipper.core.vlm.VlmEngine
import com.adskipper.core.detect.NodeMatcher
import com.adskipper.core.detect.SkipLayer
import com.adskipper.core.detect.SkipSafety
import com.adskipper.core.detect.SkipTarget
import com.adskipper.core.detect.SplashSessionTracker
import com.adskipper.core.detect.YoloSkipDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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

        @Suppress("DEPRECATION")
        fun protectedPackages(context: Context): Set<String> {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            // Web-link handlers also include embedded browsers in normal apps.
            val browser = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            val dynamic = listOf(home, browser).flatMap { intent ->
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .map { it.activityInfo.packageName }
            }
            return AppSettings.PROTECTED_PACKAGES + dynamic + setOf(
                "com.android.quicksearchbox", "com.google.android.googlequicksearchbox", "com.miui.personalassistant",
                "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.mozilla.firefox",
                "com.microsoft.emmx", "com.opera.browser", "com.brave.browser", "com.android.browser",
                "com.mi.globalbrowser", "com.heytap.browser", "com.coloros.browser", "com.vivo.browser",
                "com.huawei.browser", "com.sec.android.app.sbrowser", "com.tencent.mtt", "com.baidu.browser.apps",
                "com.UCMobile", "com.ucmobile.lite", "com.quark.browser",
            )
        }
    }
    // Serialize session, switch and foreground changes on the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = SplashSessionTracker()
    private var settings = AppSettings(masterEnabled = false)
    private var settingsRevision = 0L
    private var pollJob: Job? = null
    private var pollUpdates: Channel<Unit>? = null
    private var feedbackJob: Job? = null
    private var autoLearningJob: Job? = null
    private var demonstrationJob: Job? = null
    private var modelJob: Job? = null
    private var pollingId: Long? = null
    private lateinit var stats: StatsRepository
    private lateinit var overlay: DebugOverlay
    private lateinit var visualFallback: VisualFallback<Bitmap>
    private var learningOverlay: LearningOverlay? = null
    private var demonstrationOverlay: DemonstrationOverlay? = null
    private var demonstrationFrame: Bitmap? = null
    private var demonstrationFrameAt = 0L
    private var demonstrationWindowId = -1
    private val learnedRules by lazy { LearnedRuleRepository.get(this) }
    private var learningCaptureJob: Job? = null
    private var yolo: YoloSkipDetector? = null
    private var excludedPackages = AppSettings.PROTECTED_PACKAGES
    private var selfLabels = emptySet<String>()
    private var selfTestVisible = false
    private var lastMissReason = "未找到符合条件的跳过按钮"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                stopSession()
                learningOverlay?.hide()
                if (LearningController.state.value.phase == LearningController.Phase.CAPTURING)
                    LearningController.fail("屏幕已关闭，请重新采集")
            }
        }
    }

    override fun onServiceConnected() {
        stats = StatsRepository(this)
        overlay = DebugOverlay(this)
        learningOverlay = LearningOverlay(this)
        demonstrationOverlay = DemonstrationOverlay(this)
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
        excludedPackages = protectedPackages(this)
        selfLabels = setOf(applicationInfo.loadLabel(packageManager).toString())
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startNotification()
        mutableConnected.value = true
        scope.launch {
            LearningController.state.collect { learning ->
                if (learning.phase != LearningController.Phase.ARMED) learningOverlay?.hide()
                else { stopSession(); updateLearningOverlay() }
            }
        }
        scope.launch {
            learnedRules.rules.collect {
                settingsRevision++
                stopSession()
            }
        }
        scope.launch {
            SettingsRepository(this@AdSkipperService).settings.collect { stored ->
                val new = stored.copy(vlmEnabled = false)
                if (new != settings) {
                    settingsRevision++
                    stopSession()
                    overlay.dismiss()
                }
                settings = new
                updateLearningOverlay()
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
        val eventPackage = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && eventPackage != null &&
            eventPackage !in settings.enabledPackages &&
            !(eventPackage == packageName && settings.selfTest && selfTestVisible)) return
        // A temporarily unreadable tree is not a foreground switch. Keep the original budget.
        val pkg = rootInActiveWindow?.packageName?.toString()
            ?: event.packageName?.toString()?.takeIf {
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    (it in excludedPackages || (it != packageName && it !in settings.enabledPackages))
            } ?: return
        val activityClass = event.className?.toString()
        updateLearningOverlay()
        if (pkg == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            activityClass?.endsWith("Activity") == true) {
            selfTestVisible = activityClass.endsWith("TestAdActivity")
        }
        val allowed = settings.masterEnabled &&
            (settings.layer1Enabled || settings.layer2Enabled || settings.layer3Enabled || settings.vlmEnabled ||
                pkg in settings.demonstrationPackages ||
                learnedRules.rules.value.any { it.packageName == pkg }) &&
            !LearningController.collecting(pkg) &&
            appAllowed(pkg) &&
            getSystemService(PowerManager::class.java).isInteractive
        val session = sessions.observeForeground(pkg, allowed, SystemClock.elapsedRealtime())
        if (session == null) {
            if (!allowed) {
                pollJob?.cancel(); autoLearningJob?.cancel(); demonstrationJob?.cancel()
                clearDemonstration()
            }
            return
        }
        if (pollingId == session.id) {
            pollUpdates?.trySend(Unit)
            return
        }
        clearVisualFallback()
        pollJob?.cancel()
        feedbackJob?.cancel()
        autoLearningJob?.cancel()
        demonstrationJob?.cancel()
        clearDemonstration()
        overlay.dismiss()
        pollingId = session.id
        val updates = Channel<Unit>(Channel.CONFLATED).also { pollUpdates = it }
        lastMissReason = "未找到符合条件的跳过按钮"
        pollJob = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adskipper:splash")
            val initialVlmCount = VlmEngine.inferenceStatus.value.count
            try {
                wakeLock.acquire(8_000L)
                if (settings.debugOverlay) overlay.update("正在识别开屏")
                val attempted = pollForSkipButton(
                    updates,
                    isCurrent = { sessions.isCurrent(session, SystemClock.elapsedRealtime()) },
                    detect = { detectAndClick(session) },
                )
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
                updates.close()
                if (wakeLock.isHeld) wakeLock.release()
                if (pollingId == session.id) {
                    clearDemonstration()
                    pollingId = null
                    pollUpdates = null
                }
                if (pollingId == null) clearVisualFallback()
            }
        }
    }

    private fun stopSession() {
        clearVisualFallback()
        sessions.invalidate()
        pollJob?.cancel()
        pollJob = null
        pollUpdates = null
        pollingId = null
        feedbackJob?.cancel()
        feedbackJob = null
        autoLearningJob?.cancel()
        autoLearningJob = null
        demonstrationJob?.cancel()
        demonstrationJob = null
        clearDemonstration()
    }

    private fun clearDemonstration() {
        demonstrationOverlay?.hide()
        demonstrationFrame?.recycle()
        demonstrationFrame = null
    }

    private fun clearVisualFallback() {
        VlmEngine.cancelInference()
        if (::visualFallback.isInitialized) visualFallback.clear()
    }

    private fun appAllowed(pkg: String): Boolean = pkg !in excludedPackages &&
        if (pkg == packageName) settings.selfTest && selfTestVisible else pkg in settings.enabledPackages

    private fun validRoot(session: SplashSessionTracker.Session, windowId: Int? = null): AccessibilityNodeInfo? {
        if (!settings.masterEnabled || !sessions.isCurrent(session, SystemClock.elapsedRealtime())) return null
        if (!appAllowed(session.packageName)) return null
        if (!getSystemService(PowerManager::class.java).isInteractive) return null
        return rootInActiveWindow?.takeIf {
            if (windowId == null) it.packageName?.toString() == session.packageName
            else SkipSafety.sameWindow(session.packageName, windowId, it.packageName?.toString(), it.windowId)
        }
    }

    private suspend fun detectAndClick(session: SplashSessionTracker.Session): Boolean {
        if (LearningController.collecting(session.packageName)) return false
        val root = validRoot(session) ?: return false
        val windowId = root.windowId
        val revision = settingsRevision
        val snapshot = settings
        val demonstrating = session.packageName in snapshot.demonstrationPackages
        val rules = learnedRules.rules.value.filter { it.packageName == session.packageName }
        val appVersion = runCatching {
            packageManager.getPackageInfo(session.packageName, 0).longVersionCode
        }.getOrDefault(-1)
        val display = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val pipeline = DetectionPipeline.create(if (snapshot.layer3Enabled) yolo else null, null, selfLabels)
        val frames = ArrayList<Bitmap>()
        var ocrLines: List<OcrLine>? = null
        val started = SystemClock.elapsedRealtime()
        suspend fun pass(node: AccessibilityNodeInfo): SkipTarget? {
            val remaining = sessions.remainingMs(session, SystemClock.elapsedRealtime())
            if (remaining <= 0) return null
            LearnedMatcher.findNode(rules, node, session.packageName, appVersion, display.width(), display.height())?.let { (rule, box) ->
                return SkipTarget(box.exactCenterX(), box.exactCenterY(), SkipLayer.L0_LEARNED, rule.id)
            }
            val evidence = NodeMatcher.hasAdEvidence(node)
            if (!demonstrating && snapshot.layer1Enabled && evidence) {
                NodeMatcher.findSkipNode(node, snapshot.keywords, selfLabels)?.let { box ->
                    return SkipTarget(box.exactCenterX(), box.exactCenterY(), SkipLayer.L1_NODE)
                }
            }
            var image: Bitmap? = null
            suspend fun capture(): Bitmap? {
                if (image == null) image = ScreenshotCapturer.capture(this@AdSkipperService)?.also(frames::add)
                return image
            }
            if (rules.any { it.version == appVersion }) {
                capture()?.let { bitmap ->
                    LearnedMatcher.findVisual(rules, bitmap, session.packageName, appVersion)?.let { rule ->
                        return SkipTarget(rule.box.exactCenterX(), rule.box.exactCenterY(), SkipLayer.L0_LEARNED, rule.id)
                    }
                }
            }
            if (demonstrating) {
                capture()?.let { image ->
                    frames.remove(image)
                    demonstrationFrame?.recycle()
                    demonstrationFrame = image
                    demonstrationFrameAt = SystemClock.elapsedRealtime()
                    demonstrationWindowId = node.windowId
                }
                return null
            }
            val result = withTimeoutOrNull(remaining) {
                withContext(Dispatchers.Default) {
                    pipeline.detect(
                        node,
                        { capture() },
                        snapshot.copy(vlmEnabled = false),
                        nodeAdEvidence = evidence,
                        timeBudgetMs = remaining,
                    )
                }
            }
            ocrLines = result?.ocrLines
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
                if (demonstrating && SystemClock.elapsedRealtime() - session.startedAt >= 400L &&
                    revision == settingsRevision && validRoot(session) != null) {
                    showDemonstration(session, revision)
                }
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
            demonstrationOverlay?.hide()
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
            if (LearningController.collecting(session.packageName)) return false
            if (verified.layer == SkipLayer.L0_LEARNED &&
                (first.detail != verified.detail || learnedRules.rules.value.none { it.id == verified.detail })) return false

            // Reuse OCR frames; a first node-only lesson may capture for at most 300 ms.
            val learningImage = if (snapshot.layer2Enabled &&
                (verified.layer == SkipLayer.L1_NODE || verified.layer == SkipLayer.L2_OCR)) {
                frames.lastOrNull() ?: withTimeoutOrNull(minOf(300L,
                    sessions.remainingMs(session, SystemClock.elapsedRealtime()))) {
                    ScreenshotCapturer.capture(this@AdSkipperService)?.also(frames::add)
                }
            } else null
            val learningNodes = if (learningImage != null) LearnedMatcher.nodes(liveRoot) else emptyList()
            val nodeAdEvidence = NodeMatcher.hasAdEvidence(liveRoot)

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
                learningImage?.let { image ->
                    frames.remove(image)
                    learnAfterClick(session, revision, verified, image, ocrLines, learningNodes, appVersion, nodeAdEvidence)
                }
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

    private fun showDemonstration(session: SplashSessionTracker.Session, revision: Long) {
        if (demonstrationFrame == null || SystemClock.elapsedRealtime() - demonstrationFrameAt > 800L) {
            DemonstrationLearning.report(session.packageName, "示范需要可读截图，本轮等待截图")
            return
        }
        val shown = demonstrationOverlay?.show(
            onTouch = { touch -> recordDemonstration(session, revision, touch) },
            onCancel = {
                sessions.finish(session)
                clearDemonstration()
                DemonstrationLearning.report(session.packageName, "本轮示范已取消，下次启动继续等待")
            },
        ) == true
        if (shown) {
            DemonstrationLearning.report(session.packageName, "等待你点跳过；本次没有广告请点提示取消")
        } else {
            sessions.finish(session)
            DemonstrationLearning.report(session.packageName, "示范浮窗未能打开，请检查无障碍服务")
        }
    }

    private fun recordDemonstration(session: SplashSessionTracker.Session, revision: Long, touch: DemonstrationTouch) {
        if (!sessions.isCurrent(session, SystemClock.elapsedRealtime()) || revision != settingsRevision) return
        val source = demonstrationFrame
        val sourceAt = demonstrationFrameAt
        val sourceWindow = demonstrationWindowId
        demonstrationFrame = null
        sessions.finish(session)
        pollJob?.cancel()
        autoLearningJob?.cancel()
        demonstrationOverlay?.hide()
        overlay.dismiss()
        val pkg = session.packageName
        val started = SystemClock.elapsedRealtime()
        fun currentRoot(windowId: Int? = null): AccessibilityNodeInfo? {
            if (revision != settingsRevision || !settings.masterEnabled || !appAllowed(pkg) ||
                pkg !in settings.demonstrationPackages || LearningController.collecting(pkg) ||
                SystemClock.elapsedRealtime() - started > 3_000L ||
                !getSystemService(PowerManager::class.java).isInteractive) return null
            return rootInActiveWindow?.takeIf { root -> root.refresh() && root.packageName?.toString() == pkg &&
                (windowId == null || root.windowId == windowId) }
        }
        fun report(message: String) {
            DemonstrationLearning.report(pkg, message)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        demonstrationJob?.cancel()
        demonstrationJob = scope.launch {
            var before: Bitmap? = null
            var after: Bitmap? = null
            try {
                delay(80) // Remove the touch layer and its prompt before sampling the underlying ad.
                val root = currentRoot() ?: return@launch
                val windowId = root.windowId
                val version = packageManager.getPackageInfo(pkg, 0).longVersionCode
                val nodes = if (touch.tap) LearnedMatcher.nodes(root) else emptyList()
                if (touch.tap) before = withTimeoutOrNull(600L) { ScreenshotCapturer.capture(this@AdSkipperService) }
                if (currentRoot(windowId) == null || SystemClock.elapsedRealtime() - started > 900L) {
                    report("页面已变化，本次示范已取消")
                    return@launch
                }
                if (touch.tap) {
                    val fresh = before
                    if (fresh == null) { report("截图失败，请直接再点跳过；本轮未学习"); return@launch }
                    if (source == null || sourceWindow != windowId || started - sourceAt !in 0L..800L ||
                        !FrameVerifier.matches(source, fresh, SkipTarget(touch.x, touch.y, SkipLayer.L0_LEARNED))) {
                        report("按钮画面已变化，请直接再点跳过；本轮未学习")
                        return@launch
                    }
                }
                val completed = if (touch.tap) performClick(touch.x, touch.y)
                    else performGesture(touch.path, touch.durationMs)
                val clickedAt = SystemClock.elapsedRealtime()
                if (!completed) { report("触摸未完成，请重新操作"); return@launch }
                if (!touch.tap) { report("已转发手势，本轮未学习；下次启动继续等待"); return@launch }
                val image = before ?: run { report("已转发点击，截图失败未保存规则"); return@launch }
                val rule = withContext(Dispatchers.Default) {
                    DemonstrationLearning.propose(pkg, version, image, touch.x, touch.y, nodes)
                } ?: run { report("已转发点击，按钮特征不足，可用截图框选补充"); return@launch }
                delay(450)
                val afterRoot = currentRoot() ?: return@launch
                after = ScreenshotCapturer.capture(this@AdSkipperService) ?: run {
                    report("点击后截图失败，未保存规则"); return@launch
                }
                val confirmed = withContext(Dispatchers.Default) {
                    DemonstrationLearning.confirmsExit(rule, after!!, SystemClock.elapsedRealtime() - clickedAt)
                }
                if (currentRoot(afterRoot.windowId) == null) return@launch
                if (!confirmed) { report("点击后未确认页面离开，未保存规则"); return@launch }
                learnedRules.save(rule)
                report("已学习跳过位置，下次优先匹配；新位置仍可继续示范")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                report("本轮示范未保存，请重试")
            } finally {
                source?.recycle()
                before?.recycle()
                after?.recycle()
            }
        }
    }

    private fun learnAfterClick(session: SplashSessionTracker.Session, revision: Long, target: SkipTarget,
                                image: Bitmap, lines: List<OcrLine>?, nodes: List<LearnedNode>,
                                version: Long, nodeAdEvidence: Boolean) {
        val clickedAt = SystemClock.elapsedRealtime()
        fun allowed(): Boolean = revision == settingsRevision && settings.masterEnabled &&
            appAllowed(session.packageName) && !LearningController.collecting(session.packageName) &&
            getSystemService(PowerManager::class.java).isInteractive &&
            SystemClock.elapsedRealtime() - session.startedAt < 8_000L &&
            rootInActiveWindow?.packageName?.toString() == session.packageName
        autoLearningJob?.cancel()
        autoLearningJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var afterImage: Bitmap? = null
            try {
                withTimeoutOrNull(2_500L) {
                    val ocr = OcrDetector()
                    // ML Kit has no task cancellation; retain its bitmap until recognition finishes.
                    suspend fun recognize(bitmap: Bitmap) = withContext(NonCancellable + Dispatchers.Default) {
                        ocr.recognize(bitmap)
                    }
                    val rule = withContext(Dispatchers.Default) {
                        AutomaticLearning.propose(session.packageName, version, image, target,
                            lines?.takeIf { it.isNotEmpty() } ?: recognize(image), nodes, nodeAdEvidence)
                    } ?: return@withTimeoutOrNull
                    image.recycle()
                    delay(700)
                    if (!allowed()) return@withTimeoutOrNull
                    val afterRoot = rootInActiveWindow ?: return@withTimeoutOrNull
                    if (!afterRoot.refresh() || NodeMatcher.hasAdEvidence(afterRoot)) return@withTimeoutOrNull
                    val after = ScreenshotCapturer.capture(this@AdSkipperService) ?: return@withTimeoutOrNull
                    afterImage = after
                    val elapsed = SystemClock.elapsedRealtime() - clickedAt
                    val afterLines = recognize(after)
                    val confirmed = withContext(Dispatchers.Default) {
                        AutomaticLearning.confirmsExit(rule, after, afterLines, false, elapsed)
                    }
                    if (!confirmed || !allowed()) return@withTimeoutOrNull
                    val current = rootInActiveWindow ?: return@withTimeoutOrNull
                    if (!current.refresh() || !SkipSafety.sameWindow(session.packageName, afterRoot.windowId,
                            current.packageName?.toString(), current.windowId) || NodeMatcher.hasAdEvidence(current) ||
                        NodeMatcher.findSkipNode(current, settings.keywords, selfLabels) != null) return@withTimeoutOrNull
                    // Keep the final settings/foreground check and atomic write on the same dispatcher.
                    learnedRules.save(rule)
                    if (settings.debugOverlay) overlay.update("已自动学习跳过按钮，下次优先匹配")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (allowed() && settings.debugOverlay) overlay.update("本次规则未保存，可在应用页手动学习")
            } finally {
                if (!image.isRecycled) image.recycle()
                afterImage?.let { if (!it.isRecycled) it.recycle() }
            }
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

    private suspend fun performClick(x: Float, y: Float): Boolean = performGesture(
        Path().apply { moveTo(x, y) }, ViewConfiguration.getTapTimeout().toLong())

    private suspend fun performGesture(path: Path, durationMs: Long): Boolean = withTimeoutOrNull(durationMs + 1_000L) {
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, durationMs),
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

    private fun updateLearningOverlay() {
        val learning = LearningController.state.value
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (learning.phase == LearningController.Phase.ARMED && pkg != null && pkg == learning.packageName &&
            settings.masterEnabled && appAllowed(pkg) &&
            getSystemService(PowerManager::class.java).isInteractive) {
            learningOverlay?.show { captureLearning(pkg) }
        } else learningOverlay?.hide()
    }

    private fun captureLearning(pkg: String) {
        val id = LearningController.capturing() ?: return
        stopSession()
        overlay.dismiss()
        learningCaptureJob = scope.launch {
            var image: Bitmap? = null
            try {
                delay(150) // Let the capture button disappear before taking the screenshot.
                val root = rootInActiveWindow ?: error("无法读取前台应用")
                check(settings.masterEnabled && appAllowed(pkg) && root.packageName?.toString() == pkg &&
                    getSystemService(PowerManager::class.java).isInteractive) { "前台已变化，请重新采集" }
                val window = root.windowId
                val nodes = LearnedMatcher.nodes(root)
                val version = packageManager.getPackageInfo(pkg, 0).longVersionCode
                image = ScreenshotCapturer.capture(this@AdSkipperService) ?: error("截图失败，请重新采集")
                val after = rootInActiveWindow
                check(settings.masterEnabled && appAllowed(pkg) && after != null &&
                    SkipSafety.sameWindow(pkg, window, after.packageName?.toString(), after.windowId) &&
                    getSystemService(PowerManager::class.java).isInteractive) { "采集时页面已切换，请重试" }
                val afterNodes = LearnedMatcher.nodes(after)
                val stableNodes = if (LearnedMatcher.layout(nodes) == LearnedMatcher.layout(afterNodes)) afterNodes else emptyList()
                LearningController.captured(this@AdSkipperService, id, image!!, stableNodes, version)
            } catch (cancelled: CancellationException) {
                if (LearningController.state.value.id == id) LearningController.fail("采集已取消，请重试")
                throw cancelled
            } catch (error: Exception) {
                if (LearningController.state.value.id == id) LearningController.fail(error.message ?: "采集失败")
            } finally { image?.recycle() }
        }
    }

    override fun onInterrupt() {
        stopSession()
        learningOverlay?.hide()
        learningCaptureJob?.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mutableConnected.value = false
        learningOverlay?.hide()
        learningCaptureJob?.cancel()
        stopSession()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mutableConnected.value = false
        learningOverlay?.hide()
        learningCaptureJob?.cancel()
        stopSession()
        if (::overlay.isInitialized) overlay.dismiss()
        runCatching { unregisterReceiver(screenReceiver) }
        yolo?.close()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { VlmEngine.release() }
        super.onDestroy()
    }
}
