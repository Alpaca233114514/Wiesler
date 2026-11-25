package io.wiesler.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wiesler.android.FlowRouting
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
    onRequestUsageAccess: () -> Unit,
    onRefreshUsage: () -> Unit,
    onRefreshEgress: () -> Unit,
    onOpenScopeDialog: () -> Unit
) {
    val semantic = LocalWieslerColors.current
    var pingInput by remember(state.packetLoss.target) { mutableStateOf(state.packetLoss.target) }
    var pingError by remember { mutableStateOf<String?>(null) }

    val frame = state.studioCurrentFrame
    val directCount = frame?.flows?.count { it.routing == FlowRouting.DIRECT } ?: 0
    val proxyCount = frame?.flows?.count { it.routing == FlowRouting.PROXY } ?: 0

    val bottomClearance = LocalBottomBarClearance.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            ExpressivePageHeader(
                kicker = "RUNTIME MONITOR",
                title = "运行监测",
                subtitle = "${state.networkSummary} · 实时流量、丢包与应用排行"
            )
        }

        item(key = "egress_monitor") {
            EgressComparisonBanner(
                egress = state.egress,
                controllerEnabled = state.controllerEnabled,
                onRefresh = onRefreshEgress
            )
        }

        item(key = "mihomo_overview") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Mihomo 总览与趋势", style = MaterialTheme.typography.titleLarge)
                    }
                    FilledTonalButton(onClick = onOpenControllerDialog) {
                        Text(if (state.controllerEnabled) "控制器" else "连接控制器")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricChipTile(
                        label = "当前连接",
                        value = if (state.controllerEnabled && frame != null) "${frame.flows.size}" else "—",
                        subtitle = "直连 $directCount · 代理 $proxyCount",
                        accentColor = MaterialTheme.colorScheme.onSurface,
                        compact = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    MetricChipTile(
                        label = "下载 / 秒",
                        value = formatRate(frame?.rxTotal),
                        subtitle = frame?.downloadTotal?.let { "累计 ${formatBytes(it.toDouble())}" } ?: "等待快照",
                        accentColor = semantic.download,
                        compact = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    MetricChipTile(
                        label = "上传 / 秒",
                        value = formatRate(frame?.txTotal),
                        subtitle = frame?.uploadTotal?.let { "累计 ${formatBytes(it.toDouble())}" } ?: "等待快照",
                        accentColor = semantic.upload,
                        compact = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                DualSeriesAreaChart(
                    samples = state.mihomoTrend,
                    emptyText = if (state.controllerEnabled) "等待 Mihomo 连续采样" else "未连接 Mihomo 控制器"
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("全局手机流量", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (state.globalEnabled) "TrafficStats · 最近 60 次采样 · ${state.globalSampledAt}" else "监控已关闭",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.globalEnabled, onCheckedChange = onToggleGlobal)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricChipTile(
                        label = "全局下载",
                        value = "↓ ${formatRate(state.globalRate?.rx)}",
                        subtitle = "开机累计 ${formatBytes(state.bootRxBytes.toDouble())}",
                        accentColor = semantic.download,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChipTile(
                        label = "全局上传",
                        value = "↑ ${formatRate(state.globalRate?.tx)}",
                        subtitle = "开机累计 ${formatBytes(state.bootTxBytes.toDouble())}",
                        accentColor = semantic.upload,
                        modifier = Modifier.weight(1f)
                    )
                }
                DualSeriesAreaChart(samples = state.globalTrend)
                OutlinedButton(onClick = onOpenScopeDialog, modifier = Modifier.fillMaxWidth()) {
                    Text("数据边界说明（VPN 重复计量 / 系统口径）")
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("海外转发流量", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = state.forwardState,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.forwardEnabled, onCheckedChange = onToggleForward)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricChipTile(
                        label = "转发下载",
                        value = "↓ ${formatRate(state.forwardRate?.rx)}",
                        subtitle = "已观测 ${formatBytes(state.forwardObservedRx)}",
                        accentColor = semantic.download,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChipTile(
                        label = "转发上传",
                        value = "↑ ${formatRate(state.forwardRate?.tx)}",
                        subtitle = "已观测 ${formatBytes(state.forwardObservedTx)}",
                        accentColor = semantic.upload,
                        modifier = Modifier.weight(1f)
                    )
                }
                DualSeriesAreaChart(samples = state.forwardTrend)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenExitsDialog, modifier = Modifier.weight(1f)) {
                        Text("海外出口 (${state.overseasExits.size})")
                    }
                    FilledTonalButton(onClick = onJumpOverseasConnections, modifier = Modifier.weight(1f)) {
                        Text("查看转发连接")
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.NetworkCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("ICMP 丢包与延迟探测", style = MaterialTheme.typography.titleLarge)
                    }
                    StatusPill(
                        text = if (state.packetLoss.running) "探测中" else "待机",
                        containerColor = if (state.packetLoss.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (state.packetLoss.running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.packetLoss.running) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = pingInput,
                        onValueChange = { pingInput = it; pingError = null },
                        label = { Text("单播 IPv4 目标") },
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
                        }
                    ) {
                        Text(if (state.packetLoss.running) "停止" else "开始")
                    }
                }
                val lossPct = state.packetLoss.lossPercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—"
                val avgRtt = state.packetLoss.averageRtt?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricChipTile(
                        label = "丢包率",
                        value = lossPct,
                        accentColor = if ((state.packetLoss.lossPercent ?: 0.0) > 5.0) semantic.danger else semantic.download,
                        modifier = Modifier.weight(1f)
                    )
                    MetricChipTile(
                        label = "平均 RTT",
                        value = avgRtt,
                        accentColor = semantic.upload,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "发送 ${state.packetLoss.sent} · 收到 ${state.packetLoss.received} · 未回包 ${state.packetLoss.sent - state.packetLoss.received} · 探测错误 ${state.packetLoss.errors}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = state.packetLoss.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item(key = "apps_header") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("今日应用流量排行", style = MaterialTheme.typography.titleLarge)
                            Text("今天 00:00 起 · Wi-Fi 与移动网络", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.usageAllowed) {
                        if (state.usageBusy) {
                            LoadingIndicator(modifier = Modifier.size(32.dp))
                        } else {
                            FilledTonalButton(onClick = onRefreshUsage) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("刷新")
                            }
                        }
                    }
                }
                if (!state.usageAllowed) {
                    Text(
                        "授予系统「使用情况访问权限」后，即可查看各应用今天的下载、上传和总流量排行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRequestUsageAccess, modifier = Modifier.fillMaxWidth()) {
                        Text("前往系统授权")
                    }
                } else if (state.usageError != null) {
                    Text(state.usageError, style = MaterialTheme.typography.bodyMedium, color = semantic.danger)
                } else if (state.appUsage.isEmpty() && !state.usageBusy) {
                    Text("系统暂未提供今天的流量记录，点击右上角刷新重试。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val maxTotal = state.appUsage.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
                    state.appUsage.take(15).forEachIndexed { idx, app ->
                        val ratio = (app.totalBytes.toFloat() / maxTotal.toFloat()).coerceIn(0.04f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.ROOT, "%02d  %s", idx + 1, app.title),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatBytes(app.totalBytes.toDouble()),
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                    color = semantic.download
                                )
                            }
                            LinearWavyProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "UID ${app.uid} · ↓ ${formatBytes(app.rxBytes.toDouble())} · ↑ ${formatBytes(app.txBytes.toDouble())}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (idx < minOf(14, state.appUsage.lastIndex)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("请求日志", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("最近 ${state.requestLogs.size} / 100 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.requestLogs.isEmpty()) {
                    Text(
                        "连接控制器或执行 HTTPS 检查后显示实际 HTTP 状态码与错误信息。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.requestLogs.take(25).forEach { entry ->
                        val color = when {
                            entry.failed -> semantic.danger
                            entry.result == "成功" -> semantic.success
                            else -> semantic.warning
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${entry.time} · ${entry.source}", style = MaterialTheme.typography.labelLarge)
                                    if (!entry.error.isNullOrBlank()) {
                                        Text(entry.error, style = MaterialTheme.typography.labelMedium, color = semantic.danger)
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                StatusPill(
                                    text = entry.status?.let { "HTTP $it" } ?: "无状态码",
                                    containerColor = color.copy(alpha = 0.16f),
                                    contentColor = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
