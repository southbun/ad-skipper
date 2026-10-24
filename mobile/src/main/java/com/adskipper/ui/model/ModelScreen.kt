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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.learn.LearningController

@Composable
fun ModelScreen() {
    val app = AdSkipperApp.get(LocalContext.current)
    val manager = app.modelManager
    val status by manager.status.collectAsState()
    val learning by LearningController.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("本地 AI", style = MaterialTheme.typography.headlineMedium)
        Text("在“应用 → 学习 / 规则”中采集广告，由本地模型提议跳过按钮，再确认保存。无需账号或 Token。")
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
                    Text("模型文件已就绪，学习时按需加载")
                    Text("本次学习实际识别次数：${learning.inferenceCount}；${learning.message.ifBlank { "尚未开始学习" }}" +
                        if (learning.elapsedMs > 0) "（${learning.elapsedMs} ms）" else "")
                }
            }
        }
        Text("学习采用独立进程和 CPU，首次分析可能需要几十秒。日常跳过优先匹配已确认规则，旧大模型自动兜底保持暂停。", style = MaterialTheme.typography.bodySmall)
    }
}
