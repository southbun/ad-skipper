package com.adskipper.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.data.AppSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = AdSkipperApp.get(context)
    val settings by app.settingsRepo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("检测层级", style = MaterialTheme.typography.titleMedium)
                SettingSwitch("文字按钮识别", settings.layer1Enabled) {
                    scope.launch { app.settingsRepo.setLayer1Enabled(it) }
                }
                SettingSwitch("图片文字识别", settings.layer2Enabled) {
                    scope.launch { app.settingsRepo.setLayer2Enabled(it) }
                }
                SettingSwitch("视觉按钮识别（YOLO）", settings.layer3Enabled) {
                    scope.launch { app.settingsRepo.setLayer3Enabled(it) }
                }
                SettingSwitch("本地大模型兜底", settings.vlmEnabled) {
                    scope.launch { app.settingsRepo.setVlmEnabled(it) }
                }
                Text("视觉模型仍有兼容性问题，暂时保持关闭。已下载的模型文件会保留在手机。", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("关键词", style = MaterialTheme.typography.titleMedium)
                Text(
                    settings.keywords.joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                var input by remember { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("添加关键词") },
                        keyboardActions = KeyboardActions(onDone = { }),
                    )
                    Button(onClick = {
                        val kw = input.trim()
                        if (kw.isNotEmpty()) {
                            scope.launch {
                                app.settingsRepo.setKeywords(settings.keywords + kw)
                            }
                            input = ""
                        }
                    }) { Text("添加") }
                }
                if (settings.keywords.isNotEmpty()) {
                    Button(onClick = {
                        scope.launch {
                            app.settingsRepo.setKeywords(settings.keywords.toList().dropLast(1).toSet())
                        }
                    }) { Text("移除最后一个") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("其他", style = MaterialTheme.typography.titleMedium)
                SettingSwitch("显示点击状态", settings.debugOverlay) {
                    scope.launch { app.settingsRepo.setDebugOverlay(it) }
                }
                SettingSwitch("自测模式（对本 App 生效，用于模拟广告测试）", settings.selfTest) {
                    scope.launch { app.settingsRepo.setSelfTest(it) }
                }
            }
        }

    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
