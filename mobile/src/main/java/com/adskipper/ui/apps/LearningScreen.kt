package com.adskipper.ui.apps

import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.data.AppSettings
import com.adskipper.core.learn.LearnedRuleRepository
import com.adskipper.core.learn.LearningController
import com.adskipper.core.learn.DemonstrationLearning
import com.adskipper.core.service.AdSkipperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

@Composable
fun LearningScreen(packageName: String, onBack: () -> Unit, onModels: () -> Unit) {
    val context = LocalContext.current
    val app = AdSkipperApp.get(context)
    val scope = rememberCoroutineScope()
    val settings by app.settingsRepo.settings.collectAsState(initial = AppSettings())
    val connected by AdSkipperService.connected.collectAsState()
    val repo = remember { LearnedRuleRepository.get(context) }
    val rules by repo.rules.collectAsState()
    val state by LearningController.state.collectAsState()
    val demonstration by DemonstrationLearning.status.collectAsState()
    val current = state.takeIf { it.packageName == packageName }
    val model by app.modelManager.status.collectAsState()
    val info by produceState<Pair<String, Long>?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getPackageInfo(packageName, 0).let {
                it.applicationInfo?.loadLabel(context.packageManager).toString() to it.longVersionCode
            } }.getOrNull()
        }
    }
    val protected = remember(packageName) { packageName in AdSkipperService.protectedPackages(context) }
    val enabled = settings.masterEnabled && packageName in settings.enabledPackages && !protected
    var notice by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("返回应用列表") }
        Text("学习跳过按钮", style = MaterialTheme.typography.headlineMedium)
        Text(info?.first ?: packageName)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("持续手动示范学习", Modifier.weight(1f))
            Switch(checked = packageName in settings.demonstrationPackages,
                enabled = (connected && enabled) || packageName in settings.demonstrationPackages,
                onCheckedChange = { value -> scope.launch {
                    app.settingsRepo.setDemonstrationEnabled(packageName, value)
                } })
        }
        Text("旧规则命中就自动跳过；看到示范提示后，直接点广告的跳过按钮，核验后自动保存。换位置或样式可继续示范，无需下载模型。")
        Text("仅启动后 8 秒等待。没有广告请点浮窗取消；点按会短暂等待截图。滑动只转发，多指取消本轮；安全窗口或画面不可读时暂停示范。")
        if (demonstration.packageName == packageName && demonstration.message.isNotBlank()) Text(demonstration.message)
        if (packageName !in settings.demonstrationPackages) {
            Text("未开启示范学习时，继续普通识别，并自动学习核验成功的文字跳过按钮。")
            if (!settings.layer2Enabled) Text("图片文字识别已关闭，文字按钮自动学习暂停；示范学习和手动采集仍可使用。")
        }
        Text("也可用下方“采集新广告”截图框选，手动补充规则。")
        Text("学习只在本机进行。保存按钮特征和控件描述；完整截图在保存、放弃或进程重启时删除。")
        if (!connected) Text("请先开启无障碍服务")
        if (!enabled) Text("请先打开总开关，并在应用列表中开启此应用")
        Button(onClick = { LearningController.arm(packageName) },
            enabled = connected && enabled && !saving && current?.phase != LearningController.Phase.ANALYZING) {
            Text("采集新广告（等待 60 秒）")
        }
        if (current != null) {
            Text(current.message)
            if (current.inferenceCount > 0 || current.phase == LearningController.Phase.ANALYZING)
                Text("本次大模型调用 ${current.inferenceCount} 次 · 已用 ${current.elapsedMs / 1000} 秒")
            if (current.imagePath != null) {
                val image by produceState<android.graphics.Bitmap?>(null, current.imagePath) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(current.imagePath) }
                }
                val preview = image
                DisposableEffect(preview) { onDispose { preview?.recycle() } }
                preview?.let { bitmap ->
                    Text("检查绿色框；可在图上拖动，重新框住整个跳过按钮。图片两侧可上下滑动页面。")
                    var draft by remember(current.id) { mutableStateOf<Rect?>(null) }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val ratio = bitmap.width.toFloat() / bitmap.height
                        Box(Modifier.width(minOf(maxWidth, 420.dp * ratio)).aspectRatio(ratio).align(Alignment.Center)
                            .pointerInput(bitmap, current.id) {
                                var start = Offset.Zero
                                fun pixel(point: Offset) = Offset(
                                    (point.x / size.width * bitmap.width).coerceIn(0f, bitmap.width.toFloat()),
                                    (point.y / size.height * bitmap.height).coerceIn(0f, bitmap.height.toFloat()))
                                detectDragGestures(
                                    onDragStart = { start = pixel(it); draft = null },
                                    onDragCancel = { draft = null },
                                    onDragEnd = {
                                        draft?.let { LearningController.select(it, bitmap.width, bitmap.height) }
                                        draft = null
                                    },
                                ) { change, _ ->
                                    change.consume()
                                    val end = pixel(change.position)
                                    draft = Rect(min(start.x, end.x).toInt(), min(start.y, end.y).toInt(),
                                        max(start.x, end.x).toInt(), max(start.y, end.y).toInt())
                                }
                            }) {
                            Image(bitmap.asImageBitmap(), "已采集的广告及待确认按钮", Modifier.fillMaxSize())
                            Canvas(Modifier.fillMaxSize()) {
                                (draft ?: current.box)?.let { box ->
                                    val sx = size.width / bitmap.width
                                    val sy = size.height / bitmap.height
                                    drawRect(Color.Green, Offset(box.left * sx, box.top * sy),
                                        Size(box.width() * sx, box.height() * sy), style = Stroke(3.dp.toPx()))
                                }
                            }
                        }
                    }
                }
                if (!model.downloaded) {
                    Text("文字识别无需下载。无文字按钮可下载可选视觉模型辅助，或直接在图上框选。")
                    TextButton(onClick = onModels) { Text("下载可选视觉模型") }
                }
                OutlinedButton(onClick = { LearningController.analyze(context) },
                    enabled = current.phase != LearningController.Phase.ANALYZING && !saving) {
                    Text("本地识别按钮")
                }
                Button(onClick = {
                    saving = true
                    scope.launch { try { LearningController.save(context) } finally { saving = false } }
                }, enabled = enabled && connected && current.phase == LearningController.Phase.REVIEW && !saving) {
                    Text(if (saving) "正在保存…" else "确认这是跳过按钮，保存规则")
                }
            }
            if (current.phase != LearningController.Phase.SAVED)
                TextButton(onClick = { LearningController.discard() }, enabled = !saving) { Text("放弃本次学习") }
        }
        Text("已保存规则", style = MaterialTheme.typography.titleLarge)
        Text("每个应用保留最近 8 个位置和样式，重复样式更新。版本、尺寸或画面不匹配时，按此应用的模式继续示范或普通识别。")
        repo.error?.let { Text(it) }
        if (notice.isNotBlank()) Text(notice)
        val saved = rules.filter { it.packageName == packageName }
        if (saved.isEmpty()) Text("尚无已保存的规则")
        saved.asReversed().forEach { rule ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(rule.createdAt)))
                    Text(if (info?.second == rule.version) "${rule.width} × ${rule.height} · 版本一致"
                        else "应用版本已变化，需重新学习", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    scope.launch { notice = withContext(Dispatchers.IO) {
                        runCatching { repo.delete(rule.id); "规则已删除" }.getOrElse { "删除失败：${it.message}" }
                    } }
                }) { Text("删除") }
            }
        }
    }
}
