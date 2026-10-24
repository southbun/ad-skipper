package com.adskipper.ui.apps

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.data.AppSettings
import com.adskipper.core.service.AdSkipperService
import com.adskipper.core.learn.LearnedRuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class InstalledApp(val packageName: String, val label: String, val isProtected: Boolean = false)

internal fun filterInstalledApps(
    apps: List<InstalledApp>, query: String, enabledPackages: Set<String>, enabledOnly: Boolean,
): List<InstalledApp> {
    val term = query.trim()
    return apps.filter {
        (!enabledOnly || (!it.isProtected && it.packageName in enabledPackages)) &&
            (term.isEmpty() || it.label.contains(term, ignoreCase = true) ||
                it.packageName.contains(term, ignoreCase = true))
    }
}

@Composable
fun AppsScreen(onLearn: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = AdSkipperApp.get(context)
    val settings by app.settingsRepo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val rules by LearnedRuleRepository.get(context).rules.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var enabledOnly by rememberSaveable { mutableStateOf(false) }
    val loaded by produceState<Result<List<InstalledApp>>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val protectedPackages = AdSkipperService.protectedPackages(context)
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map {
                        val pkg = it.activityInfo.packageName
                        InstalledApp(pkg, it.loadLabel(pm).toString(), pkg in protectedPackages)
                    }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != context.packageName }
                    .sortedBy { it.label }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("应用", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Text("默认全部关闭，搜索并开启需要跳过开屏广告的应用。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索应用名称或包名") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已开启 ${settings.enabledPackages.size} 个")
            FilterChip(selected = enabledOnly, onClick = { enabledOnly = !enabledOnly },
                label = { Text("只看已开启") })
        }
        when {
            loaded == null -> Text("正在读取应用列表…")
            loaded?.isFailure == true -> Text("读取应用列表失败，请重新打开此页。")
            else -> {
                val visible = filterInstalledApps(loaded!!.getOrThrow(), query, settings.enabledPackages, enabledOnly)
                if (visible.isEmpty()) Text(if (enabledOnly) "没有匹配的已开启应用" else "没有找到匹配的应用")
                LazyColumn(Modifier.weight(1f)) {
                    items(visible, key = { it.packageName }) { item ->
                        val checked = !item.isProtected && item.packageName in settings.enabledPackages
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = checked,
                                enabled = !item.isProtected,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    scope.launch { app.settingsRepo.setAppEnabled(item.packageName, enabled) }
                                },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.isProtected) Text("系统或浏览器，保持保护", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = checked, onCheckedChange = null, enabled = !item.isProtected)
                        }
                        if (!item.isProtected && (checked || rules.any { it.packageName == item.packageName })) TextButton(onClick = { onLearn(item.packageName) }) {
                            Text("学习 / 规则（${rules.count { it.packageName == item.packageName }}）")
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
