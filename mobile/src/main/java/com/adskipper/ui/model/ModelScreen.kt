package com.adskipper.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.launch

@Composable
fun ModelScreen() {
    val app = AdSkipperApp.get(LocalContext.current)
    val manager = app.modelManager
    val status by manager.status.collectAsState()
    val engine by VlmEngine.state.collectAsState()
    val inference by VlmEngine.inferenceStatus.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("本地 AI", style = MaterialTheme.typography.headlineMedium)
        Text("快速识别未找到按钮时，由大模型分析画面。截图只在手机内处理，无需账号或 Token。")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("InternVL3-2B", style = MaterialTheme.typography.titleLarge)
                Text("模型约 1.5GB，建议连接 Wi-Fi 下载。下载完成后可离线识别。")
                if (status.running) {
                    LinearProgressIndicator(
                        progress = { (status.bytes.toFloat() / manager.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${status.message}  %.0f / %.0f MB".format(status.bytes / 1048576f, manager.totalBytes / 1048576f))
                    OutlinedButton(onClick = manager::cancelDownload) { Text("暂停下载") }
                } else if (!status.downloaded) {
                    status.message.takeIf { it.isNotEmpty() }?.let { Text(it) }
                    Button(onClick = manager::startDownload) { Text("下载本地模型") }
                } else {
                    Text(when (engine) {
                        VlmEngine.State.READY -> "模型已就绪"
                        VlmEngine.State.LOADING -> "正在加载模型…"
                        VlmEngine.State.ERROR -> "模型运行或加载失败，基础识别仍可使用"
                        VlmEngine.State.NOT_LOADED -> "模型已下载，尚未加载"
                    })
                    Text("实际识别次数：${inference.count}；${inference.message}" +
                        if (inference.elapsedMs > 0) "（${inference.elapsedMs} ms）" else "")
                    if (engine == VlmEngine.State.NOT_LOADED || engine == VlmEngine.State.ERROR) {
                        Button(onClick = {
                            scope.launch {
                                app.settingsRepo.setVlmEnabled(true)
                                manager.loadModel()
                            }
                        }) { Text("加载并启用") }
                    }
                }
            }
        }
        Text("首次加载和识别耗时请在手机上测试。只在应用切换到前台后的短时间内识别。", style = MaterialTheme.typography.bodySmall)
    }
}
