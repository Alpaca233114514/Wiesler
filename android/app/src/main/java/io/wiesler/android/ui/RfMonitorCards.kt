package io.wiesler.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wiesler.android.RfAnomalyKind
import io.wiesler.android.RfDiagnosticsSnapshot
import io.wiesler.android.RfFaultCause
import io.wiesler.android.RfSeverity
import io.wiesler.android.RfSlotSummary
import io.wiesler.android.RfTrendPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun RfMonitoringSection(
    snapshot: RfDiagnosticsSnapshot,
    onToggleRf: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onResetRf: () -> Unit
) {
    val semantic = LocalWieslerColors.current
    val health = snapshot.captureHealth
    val assess = snapshot.assessment
    val attr = snapshot.attribution
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    val severityAccent = when (assess.severity) {
        RfSeverity.SEVERE_POLLUTION -> semantic.danger
        RfSeverity.INTERFERENCE -> semantic.danger
        RfSeverity.WATCH -> semantic.warning
        RfSeverity.CLEAR -> semantic.success
        RfSeverity.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val attributionAccent = when (attr.cause) {
        RfFaultCause.RF_AIR_INTERFACE, RfFaultCause.COMPOUND_FAULT -> semantic.danger
        RfFaultCause.PROXY_OR_EGRESS, RfFaultCause.UPSTREAM_LINK -> semantic.warning
        RfFaultCause.HEALTHY -> semantic.success
        RfFaultCause.INSUFFICIENT_EVIDENCE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ExpressiveSectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.CellTower, contentDescription = null, tint = severityAccent)
                Column {
                    Text("蜂窝射频污染监测与归因", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = health.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = health.active,
                onCheckedChange = { enabled ->
                    if (enabled && !health.permissionGranted) {
                        onRequestPermissions()
                    } else {
                        onToggleRf(enabled)
                    }
                }
            )
        }

        if (!health.permissionGranted) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "需授予「精确定位」与「读取手机状态」权限，以主动调用 requestCellInfoUpdate 绕过息屏缓存冻结并读取双卡 RSRP / RSRQ / SINR。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("授予权限并开启射频监测")
                    }
                }
            }
        }

        // 检测 1：主动多卡采集与防冻结去重状态 / Check 1: Active dual-SIM capture & anti-freeze deduplication
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricChipTile(
                label = "新鲜基带帧",
                value = "${health.freshFrames}",
                subtitle = health.lastFreshAtMs?.let { "更新 ${timeFmt.format(Date(it))}" } ?: "尚无新鲜样本",
                accentColor = semantic.download,
                compact = true,
                modifier = Modifier.weight(1f)
            )
            MetricChipTile(
                label = "过滤缓存帧",
                value = "${health.cachedDuplicates}",
                subtitle = if (health.frozenSlotCount > 0) "${health.frozenSlotCount} 卡缓存冻结" else "时间戳去重正常",
                accentColor = if (health.frozenSlotCount > 0) semantic.warning else MaterialTheme.colorScheme.onSurface,
                compact = true,
                modifier = Modifier.weight(1f)
            )
            MetricChipTile(
                label = "污染风险分",
                value = if (assess.severity == RfSeverity.IDLE) "—" else "${assess.score}",
                subtitle = assess.severity.label,
                accentColor = severityAccent,
                compact = true,
                modifier = Modifier.weight(1f)
            )
        }

        if (health.active) {
            LinearWavyProgressIndicator(
                progress = { (assess.score / 100f).coerceIn(0.03f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = severityAccent
            )
        }

        // 检测 2：多卡联合射频污染评分器 / Check 2: Dual-SIM RF contamination scorer
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = severityAccent.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, severityAccent.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = severityAccent, modifier = Modifier.size(18.dp))
                        Text("多卡污染评分器", style = MaterialTheme.typography.labelLarge, color = severityAccent)
                    }
                    StatusPill(
                        text = if (assess.dualSimCoincidence) "双卡跨网并发" else assess.severity.label,
                        containerColor = severityAccent.copy(alpha = 0.18f),
                        contentColor = severityAccent
                    )
                }
                Text(assess.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    assess.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusPill(
                        text = "剪刀差(30s) ${assess.scissorsAlerts}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (assess.scissorsAlerts > 0) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StatusPill(
                        text = "底板失步(30s) ${assess.syncLossAlerts}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (assess.syncLossAlerts > 0) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StatusPill(
                        text = "乒乓切换(60s) ${assess.pingPongAlerts}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (assess.pingPongAlerts >= 4) semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 各卡槽明细 + 迷你 RSRP/SINR 曲线 / Per-SIM slot telemetry + mini trend chart
        if (assess.slots.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                assess.slots.forEach { slot ->
                    RfSlotCard(slot = slot)
                }
            }
        }

        // 检测 3：协议栈与业务层联合归因 / Check 3: Cross-layer RF vs proxy/egress attribution
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, attributionAccent.copy(alpha = 0.42f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.TrackChanges, contentDescription = null, tint = attributionAccent, modifier = Modifier.size(18.dp))
                        Text("射频 / 代理联合归因（60s 对齐）", style = MaterialTheme.typography.labelLarge, color = attributionAccent)
                    }
                    StatusPill(
                        text = "置信度 · ${attr.confidence}",
                        containerColor = attributionAccent.copy(alpha = 0.16f),
                        contentColor = attributionAccent
                    )
                }
                Text(attr.title, style = MaterialTheme.typography.titleMedium)
                Text(attr.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val icmpSummary = if (attr.icmpSampleCount > 0) {
                    String.format(
                        Locale.ROOT,
                        "ICMP 丢包 %.1f%% · 均延迟 %s（%d 样本）",
                        attr.icmpLossPercent ?: 0.0,
                        attr.icmpAvgRttMs?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "—",
                        attr.icmpSampleCount
                    )
                } else {
                    "ICMP 未采样"
                }
                val proxySummary = when (attr.proxyHealthy) {
                    true -> "代理正常"
                    false -> "代理异常"
                    null -> "代理未连接"
                }
                val cellSummary = when (attr.cellularValidated) {
                    true -> "蜂窝校验通过"
                    false -> "蜂窝校验失效"
                    null -> "蜂窝校验待观测"
                }
                Text(
                    text = "$icmpSummary · $proxySummary · $cellSummary",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "建议：${attr.suggestion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 最近射频告警时间线 / Recent RF anomaly timeline
        if (assess.recentEvents.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Text("近期射频告警事件", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                assess.recentEvents.take(6).forEach { ev ->
                    val evColor = when (ev.kind) {
                        RfAnomalyKind.DUAL_SIM_POLLUTION,
                        RfAnomalyKind.RF_SYNC_LOSS,
                        RfAnomalyKind.RF_INTERFERENCE -> semantic.danger
                        RfAnomalyKind.CELL_PINGPONG,
                        RfAnomalyKind.CACHE_FREEZE -> semantic.warning
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${timeFmt.format(Date(ev.observedAtMs))} · ${ev.title}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = evColor
                                )
                                Text(
                                    ev.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (health.freshFrames > 0 || health.cachedDuplicates > 0) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onResetRf) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("清空本次射频滑窗")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RfSlotCard(slot: RfSlotSummary) {
    val semantic = LocalWieslerColors.current
    val hasDanger = RfAnomalyKind.RF_INTERFERENCE in slot.activeFlags || RfAnomalyKind.RF_SYNC_LOSS in slot.activeFlags
    val hasWarn = RfAnomalyKind.CELL_PINGPONG in slot.activeFlags || slot.cacheFrozen
    val borderColor = when {
        hasDanger -> semantic.danger.copy(alpha = 0.50f)
        hasWarn -> semantic.warning.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "卡 ${slot.slotIndex + 1} · ${slot.operatorName}${if (slot.plmn.isNotBlank()) " (${slot.plmn})" else ""}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${slot.bandLabel}${if (slot.carrierAggregation) " · CA" else ""}${slot.arfcn?.let { " · ARFCN $it" } ?: ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = if (!slot.inService) "无服务" else slot.rat.label,
                    containerColor = if (hasDanger) semantic.danger.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (hasDanger) semantic.danger else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RfValueChip(
                    label = "RSRP",
                    value = slot.rsrpDbm?.let { "$it dBm" } ?: "—",
                    accent = if ((slot.rsrpDbm ?: 0) <= -140) semantic.danger else semantic.download,
                    modifier = Modifier.weight(1f)
                )
                RfValueChip(
                    label = "RSRQ",
                    value = slot.rsrqDb?.let { "$it dB" } ?: "—",
                    accent = if ((slot.rsrqDb ?: 0) <= -15) semantic.danger else semantic.upload,
                    modifier = Modifier.weight(1f)
                )
                RfValueChip(
                    label = "SINR",
                    value = slot.sinrDb?.let { "${if (it > 0) "+" else ""}$it dB" } ?: "—",
                    accent = if ((slot.sinrDb ?: 99) <= 0) semantic.danger else semantic.success,
                    modifier = Modifier.weight(1f)
                )
            }

            if (slot.trend.size >= 2) {
                RfMiniTrendChart(trend = slot.trend)
            }

            val corrText = slot.rsrpSinrCorrelation?.let { String.format(Locale.ROOT, "r(RSRP,SINR)=%+.2f", it) } ?: "r=采样不足"
            Text(
                text = "新鲜 ${slot.freshFrames} · 去重 ${slot.cachedDuplicates} · 60s切换 ${slot.pingPongTransitions60s} · $corrText",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (slot.activeFlags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    slot.activeFlags.forEach { flag ->
                        val color = if (flag == RfAnomalyKind.RF_INTERFERENCE || flag == RfAnomalyKind.RF_SYNC_LOSS) {
                            semantic.danger
                        } else {
                            semantic.warning
                        }
                        StatusPill(
                            text = flag.label,
                            containerColor = color.copy(alpha = 0.16f),
                            contentColor = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RfValueChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RfMiniTrendChart(trend: List<RfTrendPoint>) {
    val semantic = LocalWieslerColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(42.dp)) {
            val w = size.width
            val h = size.height
            drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)
            val n = trend.size.coerceAtLeast(2)
            val stepX = w / (n - 1).toFloat()

            // SINR 曲线 (-20..+30 dB) 与剪刀差红点叠加绘制。 / Plot SINR (-20..+30 dB) and highlight flagged points.
            val sinrPath = Path()
            var started = false
            trend.forEachIndexed { idx, pt ->
                val sinr = pt.sinrDb ?: return@forEachIndexed
                val norm = ((sinr + 20f) / 50f).coerceIn(0f, 1f)
                val x = idx * stepX
                val y = h - norm * h
                if (!started) {
                    sinrPath.moveTo(x, y)
                    started = true
                } else {
                    sinrPath.lineTo(x, y)
                }
                if (pt.flagged) {
                    drawCircle(color = semantic.danger, radius = 3.5f, center = Offset(x, y))
                }
            }
            if (started) {
                drawPath(sinrPath, color = semantic.download, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
            }

            // RSRP 曲线 (-156..-50 dBm)。 / Plot RSRP (-156..-50 dBm) as dashed/secondary line.
            val rsrpPath = Path()
            var rsrpStarted = false
            trend.forEachIndexed { idx, pt ->
                val rsrp = pt.rsrpDbm ?: return@forEachIndexed
                val norm = ((rsrp + 156f) / 106f).coerceIn(0f, 1f)
                val x = idx * stepX
                val y = h - norm * h
                if (!rsrpStarted) {
                    rsrpPath.moveTo(x, y)
                    rsrpStarted = true
                } else {
                    rsrpPath.lineTo(x, y)
                }
            }
            if (rsrpStarted) {
                drawPath(rsrpPath, color = semantic.upload.copy(alpha = 0.75f), style = Stroke(width = 1.6f, cap = StrokeCap.Round))
            }
        }
    }
}
