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
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("���� AI", style = MaterialTheme.typography.headlineMedium)
        Text("����ʶ��δ�ҵ���ťʱ���ɴ�ģ�ͷ������档��ͼֻ���ֻ��ڴ����������˺Ż� Token��")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("InternVL3-2B", style = MaterialTheme.typography.titleLarge)
                Text("ģ��Լ 1.5GB���������� Wi-Fi ���ء�������ɺ������ʶ��")
                if (status.running) {
                    LinearProgressIndicator(
                        progress = { (status.bytes.toFloat() / manager.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${status.message}  %.0f / %.0f MB".format(status.bytes / 1048576f, manager.totalBytes / 1048576f))
                    OutlinedButton(onClick = manager::cancelDownload) { Text("��ͣ����") }
                } else if (!status.downloaded) {
                    status.message.takeIf { it.isNotEmpty() }?.let { Text(it) }
                    Button(onClick = manager::startDownload) { Text("���ر���ģ��") }
                } else {
                    Text(when (engine) {
                        VlmEngine.State.READY -> "ģ���Ѿ���"
                        VlmEngine.State.LOADING -> "���ڼ���ģ�͡�"
                        VlmEngine.State.ERROR -> "ģ�ͼ���ʧ�ܣ�����ʶ���Կ�ʹ��"
                        VlmEngine.State.NOT_LOADED -> "ģ�������أ���δ����"
                    })
                    if (engine == VlmEngine.State.NOT_LOADED || engine == VlmEngine.State.ERROR) {
                        Button(onClick = {
                            scope.launch {
                                app.settingsRepo.setVlmEnabled(true)
                                manager.loadModel()
                            }
                        }) { Text("���ز�����") }
                    }
                }
            }
        }
        Text("�״μ��غ�ʶ���ʱ�����ֻ��ϲ��ԡ�ֻ��Ӧ���л���ǰ̨��Ķ�ʱ����ʶ��", style = MaterialTheme.typography.bodySmall)
    }
}
