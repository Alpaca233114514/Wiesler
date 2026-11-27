package io.wiesler.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wiesler.android.formatBytes
import io.wiesler.android.formatRate
import io.wiesler.android.validPingTarget
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MonitorScreen(
    state: WieslerUiState,
    onToggleGlobal: (Boolean) -> Unit,
    onToggleForward: (Boolean) -> Unit,
    onOpenControllerDialog: () -> Unit,
    onOpenExitsDialog: () -> Unit,
    onJumpOverseasConnections: () -> Unit,
    onStartLoss: (String) -> Unit,
    onStopLoss: () -> Unit,
    onToggleRfMonitor: (Boolean) -> Unit,
    onRequestRfPermissions: () -> Unit,
    onResetRfMonitor: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRefreshUsage: () -> Unit,
    onRefreshEgress: () -> Unit,
    onOpenScopeDialog: () -> Unit
) {
    val semantic = LocalWieslerColors.current
    var pingInput by remember(state.packetLoss.target) { mutableStateOf(state.packetLoss.target) }
    var pingError by remember { mutableStateOf<String?>(null) }

    val frame = state.studioCurrentFrame
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = topClearance + 12.dp,
            bottom = bottomClearance + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            ExpressivePageHeader(title = "监测")
        }

        item(key = "egress_monitor") {
            EgressComparisonBanner(
                egress = state.egress,
                controllerEnabled = state.controllerEnabled,
                onRefresh = onRefreshEgress
            )
        }

        item(key = "rf_monitoring") {
            RfMonitoringSection(
                snapshot = state.rfDiagnostics,
                onToggleRf = onToggleRfMonitor,
                onRequestPermissions = onRequestRfPermissions,
                onResetRf = onResetRfMonitor
            )
        }

        item(key = "mihomo_overview") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mihomo 总览", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onOpenControllerDialog) {
                        Text(if (state.controllerEnabled) "控制器" else "连接")
                    }
                }
                MetricStatStrip(
                    items = listOf(
                        StatMetricItem(
                            label = "连接",
                            value = if (state.controllerEnabled && frame != null) "${frame.flows.size}" else "—"
                        ),
                        StatMetricItem(
                            label = "下载",
                            value = formatRate(frame?.rxTotal),
                            accent = semantic.download
                        ),
                        StatMetricItem(
                            label = "上传",
                            value = formatRate(frame?.txTotal),
                            accent = semantic.upload
                        )
                    ),
                    compact = true
                )
                DualSeriesAreaChart(
                    samples = state.mihomoTrend,
                    emptyText = if (state.controllerEnabled) "等待采样" else "未连接控制器"
                )
            }
        }

        item(key = "global_traffic") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全局流量", style = MaterialTheme.typography.titleLarge)
                    Switch(checked = state.globalEnabled, onCheckedChange = onToggleGlobal)
                }
                MetricStatStrip(
                    items = listOf(
                        StatMetricItem(
                            label = "下载",
                            value = formatRate(state.globalRate?.rx),
                            accent = semantic.download
                        ),
                        StatMetricItem(
                            label = "上传",
                            value = formatRate(state.globalRate?.tx),
                            accent = semantic.upload
                        )
                    )
                )
                DualSeriesAreaChart(samples = state.globalTrend)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "开机 ↓ ${formatBytes(state.bootRxBytes.toDouble())} · ↑ ${formatBytes(state.bootTxBytes.toDouble())}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onOpenScopeDialog,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("口径")
                    }
                }
            }
        }

        item(key = "overseas_forward") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("海外转发", style = MaterialTheme.typography.titleLarge)
                    Switch(checked = state.forwardEnabled, onCheckedChange = onToggleForward)
                }
                MetricStatStrip(
                    items = listOf(
                        StatMetricItem(
                            label = "下载",
                            value = formatRate(state.forwardRate?.rx),
                            accent = semantic.download
                        ),
                        StatMetricItem(
                            label = "上传",
                            value = formatRate(state.forwardRate?.tx),
                            accent = semantic.upload
                        )
                    )
                )
                DualSeriesAreaChart(samples = state.forwardTrend)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = onOpenExitsDialog,
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("出口 (${state.overseasExits.size})")
                    }
                    FilledTonalButton(
                        onClick = onJumpOverseasConnections,
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("查看连接")
                    }
                }
            }
        }

        item(key = "icmp_loss") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ICMP 探测", style = MaterialTheme.typography.titleLarge)
                    StatusPill(
                        text = if (state.packetLoss.running) "运行中" else "待机",
                        containerColor = Color.Transparent,
                        contentColor = if (state.packetLoss.running) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        showDot = state.packetLoss.running
                    )
                }
                if (state.packetLoss.running) {
                    // 用滑窗采样进度条替代无限 120fps 动画，避免探测期间持续触发整屏重绘与 GPU 模糊。
                    // Replace indeterminate continuous animation with sliding-window progress to avoid 120fps redraws.
                    MinimalLinearBar(
                        progress = (state.packetLoss.sent / 60f).coerceIn(0.08f, 1f),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = pingInput,
                        onValueChange = { pingInput = it; pingError = null },
                        label = { Text("IPv4 目标") },
                        enabled = !state.packetLoss.running,
                        singleLine = true,
                        isError = pingError != null,
                        supportingText = pingError?.let { msg -> { Text(msg) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (state.packetLoss.running) {
                                onStopLoss()
                            } else {
                                val target = pingInput.trim()
                                if (!validPingTarget(target)) {
                                    pingError = "请输入单播 IPv4"
                                } else {
                                    onStartLoss(target)
                                }
                            }
                        },
                        colors = wieslerPrimaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Text(if (state.packetLoss.running) "停止" else "开始")
                    }
                }
                val lossPct = state.packetLoss.lossPercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—"
                val avgRtt = state.packetLoss.averageRtt?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"
                MetricStatStrip(
                    items = listOf(
                        StatMetricItem(
                            label = "丢包率",
                            value = lossPct,
                            accent = if ((state.packetLoss.lossPercent ?: 0.0) > 5.0) semantic.danger else semantic.download
                        ),
                        StatMetricItem(
                            label = "平均 RTT",
                            value = avgRtt,
                            accent = semantic.upload
                        )
                    )
                )
                if (state.packetLoss.sent > 0 || state.packetLoss.errors > 0) {
                    Text(
                        text = "发 ${state.packetLoss.sent} · 收 ${state.packetLoss.received} · 丢 ${state.packetLoss.sent - state.packetLoss.received} · 错 ${state.packetLoss.errors}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item(key = "apps_header") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("今日应用流量", style = MaterialTheme.typography.titleLarge)
                    if (state.usageAllowed) {
                        if (state.usageBusy) {
                            LoadingIndicator(modifier = Modifier.size(26.dp))
                        } else {
                            IconButton(
                                onClick = onRefreshUsage,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = "刷新",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
                if (!state.usageAllowed) {
                    Button(
                        onClick = onRequestUsageAccess,
                        colors = wieslerPrimaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("授予使用情况访问权限")
                    }
                } else if (state.usageError != null) {
                    Text(state.usageError, style = MaterialTheme.typography.bodyMedium, color = semantic.danger)
                } else if (state.appUsage.isEmpty() && !state.usageBusy) {
                    Text("暂无今日记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val maxTotal = state.appUsage.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.appUsage.take(15).forEachIndexed { idx, app ->
                            val ratio = (app.totalBytes.toFloat() / maxTotal.toFloat()).coerceIn(0.04f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = app.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatBytes(app.totalBytes.toDouble()),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                        color = semantic.download
                                    )
                                }
                                MinimalLinearBar(progress = ratio, color = semantic.download)
                            }
                            if (idx < minOf(14, state.appUsage.lastIndex)) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "request_logs") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("请求日志", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${state.requestLogs.size}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.requestLogs.isEmpty()) {
                    Text(
                        "暂无记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.requestLogs.take(25).forEachIndexed { idx, entry ->
                            val color = when {
                                entry.failed -> semantic.danger
                                entry.result == "成功" -> semantic.success
                                else -> semantic.warning
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "${entry.time} · ${entry.source}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!entry.error.isNullOrBlank()) {
                                        Text(
                                            text = entry.error,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = semantic.danger,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                StatusPill(
                                    text = entry.status?.let { "$it" } ?: "ERR",
                                    containerColor = Color.Transparent,
                                    contentColor = color,
                                    showDot = true
                                )
                            }
                            if (idx < minOf(24, state.requestLogs.lastIndex)) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
