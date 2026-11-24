package io.wiesler.android.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.wiesler.android.DiscoveredController
import io.wiesler.android.ThemePreference

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    state: WieslerUiState,
    onScanControllers: () -> Unit,
    onConnectDiscovered: (DiscoveredController) -> Unit,
    onOpenManualController: () -> Unit,
    onDisconnectController: () -> Unit,
    onOpenExitsDialog: () -> Unit,
    onTogglePulseAnimation: (Boolean) -> Unit,
    onToggleExpectProxy: (Boolean) -> Unit,
    onChangeThemeMode: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onOpenScopeDialog: () -> Unit
) {
    val semantic = LocalWieslerColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            ExpressivePageHeader(
                kicker = "PREFERENCES & CONTROLLER",
                title = "设置",
                subtitle = "自动发现本机控制器、海外出口、图谱动效与 Material 3 Expressive 外观"
            )
        }

        item(key = "controller") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Mihomo 控制器发现与连接", style = MaterialTheme.typography.titleLarge)
                    }
                    if (state.discoveringControllers) {
                        ContainedLoadingIndicator(modifier = Modifier.size(36.dp))
                    }
                }
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (state.controllerEnabled) state.controllerAddress else "当前未启用控制器",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
                        )
                        Text(
                            text = state.connectionState,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.hasSecret) {
                            Text(
                                text = "已使用 Android Keystore AES-GCM 绑定地址加密保存 Secret",
                                style = MaterialTheme.typography.labelMedium,
                                color = semantic.success
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onScanControllers,
                        enabled = !state.discoveringControllers,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (state.discoveringControllers) "扫描中…" else "扫描本机端口")
                    }
                    FilledTonalButton(onClick = onOpenManualController, modifier = Modifier.weight(1f)) {
                        Text("手动配置 / Secret")
                    }
                }
                if (state.controllerEnabled) {
                    OutlinedButton(onClick = onDisconnectController, modifier = Modifier.fillMaxWidth()) {
                        Text("断开连接并清除保存的 Secret")
                    }
                }
                Text(state.discoveryStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (state.discoveredControllers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.discoveredControllers.forEach { item ->
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.address, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                                        Text(
                                            text = when {
                                                item.ready -> "Mihomo ${item.version ?: ""} · 可直接连接"
                                                item.requiresAuth -> "检测到 Mihomo · 需要填写 Secret"
                                                else -> "已发现控制器"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (item.ready) semantic.success else semantic.warning
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(onClick = { onConnectDiscovered(item) }) {
                                        Text(if (item.requiresAuth) "配置密钥" else "连接")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "exits_and_workbench") {
            ExpressiveSectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("工作台与核查偏好", style = MaterialTheme.typography.titleLarge)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("图谱实时脉冲粒子", style = MaterialTheme.typography.titleMedium)
                        Text("按连续快照字节增量展示上传/下载流向动效", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.preferences.pulseAnimation, onCheckedChange = onTogglePulseAnimation)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐私全代理核查", style = MaterialTheme.typography.titleMedium)
                        Text("将 Mihomo 报告的 DIRECT 连接标记为与代理预期不符", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.preferences.expectProxy, onCheckedChange = onToggleExpectProxy)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("海外转发监控出口", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (state.overseasExits.isEmpty()) "尚未指定出口节点" else state.overseasExits.joinToString("、"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(onClick = onOpenExitsDialog) { Text("管理") }
                }
            }
        }

        item(key = "appearance") {
            ExpressiveSectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Material 3 Expressive 外观", style = MaterialTheme.typography.titleLarge)
                }
                Text("配色模式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExpressiveConnectedChoiceRow(
                    options = ThemePreference.entries.map { it to it.label },
                    selected = state.preferences.themeMode,
                    onSelect = onChangeThemeMode
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Material You 动态取色", style = MaterialTheme.typography.titleMedium)
                            Text("跟随系统壁纸生成 Expressive 色彩方案（关闭使用 Wiesler 深海薄荷配色）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.preferences.dynamicColor, onCheckedChange = onToggleDynamicColor)
                    }
                }
            }
        }

        item(key = "about") {
            ExpressiveSectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Wiesler Android 0.1.1", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "只读网络观察工具，不启用 VpnService，不修改或切换手机现有代理。开源协议：Apache-2.0。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenScopeDialog, modifier = Modifier.fillMaxWidth()) {
                    Text("查看数据来源与统计边界")
                }
            }
        }
    }
}
