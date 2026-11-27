package io.wiesler.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

@OptIn(ExperimentalLayoutApi::class)
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
        RfSeverity.SEVERE_POLLUTION,
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
            Text("蜂窝射频", style = MaterialTheme.typography.titleLarge)
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
            Button(
                onClick = onRequestPermissions,
                colors = wieslerPrimaryButtonColors(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("授予权限并开启射频监测")
            }
        }

        MetricStatStrip(
            items = listOf(
                StatMetricItem(
                    label = "风险分",
                    value = if (assess.severity == RfSeverity.IDLE) "—" else "${assess.score}",
                    accent = severityAccent
                ),
                StatMetricItem(
                    label = "新鲜帧",
                    value = "${health.freshFrames}",
                    accent = semantic.download
                ),
                StatMetricItem(
                    label = "去重帧",
                    value = "${health.cachedDuplicates}",
                    accent = if (health.frozenSlotCount > 0) semantic.warning else null
                )
            ),
            compact = true
        )

        if (health.active) {
            MinimalLinearBar(
                progress = (assess.score / 100f).coerceIn(0.03f, 1f),
                color = severityAccent
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = assess.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(
                    text = if (assess.dualSimCoincidence) "双卡并发" else assess.severity.label,
                    containerColor = Color.Transparent,
                    contentColor = severityAccent,
                    showDot = true
                )
            }
            if (assess.severity != RfSeverity.IDLE) {
                Text(
                    text = assess.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (assess.scissorsAlerts > 0 || assess.syncLossAlerts > 0 || assess.pingPongAlerts > 0) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (assess.scissorsAlerts > 0) {
                        StatusPill("剪刀差 ${assess.scissorsAlerts}", Color.Transparent, semantic.danger, showDot = true)
                    }
                    if (assess.syncLossAlerts > 0) {
                        StatusPill("失步 ${assess.syncLossAlerts}", Color.Transparent, semantic.danger, showDot = true)
                    }
                    if (assess.pingPongAlerts > 0) {
                        StatusPill("乒乓 ${assess.pingPongAlerts}", Color.Transparent, semantic.warning, showDot = true)
                    }
                }
            }
        }

        if (assess.slots.isNotEmpty()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                assess.slots.forEachIndexed { idx, slot ->
                    RfSlotRow(slot = slot)
                    if (idx < assess.slots.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = attr.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(
                    text = attr.confidence,
                    containerColor = Color.Transparent,
                    contentColor = attributionAccent
                )
            }
            Text(
                text = attr.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (attr.cause != RfFaultCause.INSUFFICIENT_EVIDENCE && attr.cause != RfFaultCause.HEALTHY) {
                Text(
                    text = attr.suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = attributionAccent
                )
            }
        }

        if (assess.recentEvents.isNotEmpty()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                assess.recentEvents.take(5).forEach { ev ->
                    val evColor = when (ev.kind) {
                        RfAnomalyKind.DUAL_SIM_POLLUTION,
                        RfAnomalyKind.RF_SYNC_LOSS,
                        RfAnomalyKind.RF_INTERFERENCE -> semantic.danger
                        RfAnomalyKind.CELL_PINGPONG,
                        RfAnomalyKind.CACHE_FREEZE -> semantic.warning
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ev.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = evColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeFmt.format(Date(ev.observedAtMs)),
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (health.freshFrames > 0 || health.cachedDuplicates > 0) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onResetRf,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("清空滑窗")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RfSlotRow(slot: RfSlotSummary) {
    val semantic = LocalWieslerColors.current
    val hasDanger = RfAnomalyKind.RF_INTERFERENCE in slot.activeFlags || RfAnomalyKind.RF_SYNC_LOSS in slot.activeFlags

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SIM ${slot.slotIndex + 1} · ${slot.operatorName} · ${slot.bandLabel}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (!slot.inService) "无服务" else slot.rat.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (hasDanger) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MetricStatStrip(
            items = listOf(
                StatMetricItem(
                    label = "RSRP",
                    value = slot.rsrpDbm?.let { "$it dBm" } ?: "—",
                    accent = if ((slot.rsrpDbm ?: 0) <= -140) semantic.danger else semantic.download
                ),
                StatMetricItem(
                    label = "RSRQ",
                    value = slot.rsrqDb?.let { "$it dB" } ?: "—",
                    accent = if ((slot.rsrqDb ?: 0) <= -15) semantic.danger else semantic.upload
                ),
                StatMetricItem(
                    label = "SINR",
                    value = slot.sinrDb?.let { "${if (it > 0) "+" else ""}$it dB" } ?: "—",
                    accent = if ((slot.sinrDb ?: 99) <= 0) semantic.danger else semantic.success
                )
            ),
            compact = true
        )

        if (slot.trend.size >= 2) {
            RfMiniTrendChart(trend = slot.trend)
        }

        if (slot.activeFlags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                slot.activeFlags.forEach { flag ->
                    val color = if (flag == RfAnomalyKind.RF_INTERFERENCE || flag == RfAnomalyKind.RF_SYNC_LOSS) {
                        semantic.danger
                    } else {
                        semantic.warning
                    }
                    StatusPill(
                        text = flag.label,
                        containerColor = Color.Transparent,
                        contentColor = color,
                        showDot = true
                    )
                }
            }
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
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            val w = size.width
            val h = size.height
            drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 0.8f)
            val n = trend.size.coerceAtLeast(2)
            val stepX = w / (n - 1).toFloat()

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
                    drawCircle(color = semantic.danger, radius = 3.2f, center = Offset(x, y))
                }
            }
            if (started) {
                drawPath(sinrPath, color = semantic.download, style = Stroke(width = 2f, cap = StrokeCap.Round))
            }

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
                drawPath(rsrpPath, color = semantic.upload.copy(alpha = 0.72f), style = Stroke(width = 1.5f, cap = StrokeCap.Round))
            }
        }
    }
}
