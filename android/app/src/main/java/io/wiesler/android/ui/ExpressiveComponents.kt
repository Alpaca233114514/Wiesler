package io.wiesler.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wiesler.android.Rate
import io.wiesler.android.RouteNode
import io.wiesler.android.RouteNodeKind
import io.wiesler.android.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExpressiveSectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun ExpressivePageHeader(
    kicker: String,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = kicker.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ExpressiveConnectedChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false
) {
    val rowModifier = if (scrollable) {
        modifier.horizontalScroll(rememberScrollState())
    } else {
        modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, (value, label) ->
            val shape = when {
                options.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { if (it) onSelect(value) },
                shapes = shape,
                modifier = if (scrollable) Modifier else Modifier.weight(1f),
                colors = ToggleButtonDefaults.tonalToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.onBackground,
                    checkedContentColor = MaterialTheme.colorScheme.background
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EgressComparisonBanner(
    egress: EgressUiState,
    controllerEnabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalWieslerColors.current
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    val sameExit = egress.system != null && egress.proxy != null && egress.system.ip == egress.proxy.ip
    val splitExit = egress.system != null && egress.proxy != null && egress.system.ip != egress.proxy.ip

    ExpressiveSectionCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text("双路公网出口对比", style = MaterialTheme.typography.titleMedium)
                if (sameExit) {
                    StatusPill(
                        text = "同出口",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else if (splitExit) {
                    StatusPill(
                        text = "已分流",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            if (egress.busy) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            } else {
                FilledTonalIconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新公网出口")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EgressRouteBox(
                title = "系统路由出口",
                ip = egress.system?.ip ?: "—",
                location = egress.system?.location?.summary()
                    ?: egress.system?.locationError
                    ?: egress.systemError
                    ?: "点击右侧刷新探测",
                timestamp = egress.system?.let { timeFormat.format(Date(it.observedAt)) } ?: "尚未探测",
                accent = semantic.upload,
                modifier = Modifier.weight(1f)
            )
            EgressRouteBox(
                title = "代理路由出口",
                ip = egress.proxy?.ip ?: "—",
                location = egress.proxy?.location?.summary()
                    ?: egress.proxy?.locationError
                    ?: egress.proxyError
                    ?: if (controllerEnabled) "点击刷新经 Mihomo 探测" else "未连接控制器",
                timestamp = egress.proxy?.let { timeFormat.format(Date(it.observedAt)) }
                    ?: if (controllerEnabled) "尚未探测" else "待连接",
                accent = semantic.download,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EgressRouteBox(
    title: String,
    ip: String,
    location: String,
    timestamp: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = accent)
                Text(timestamp, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = ip,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = location,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DualSeriesAreaChart(
    samples: List<Rate?>,
    modifier: Modifier = Modifier,
    emptyText: String = "等待连续采样"
) {
    val semantic = LocalWieslerColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val hasValid = samples.any { it != null }
    val peak = maxOf(1024.0, samples.filterNotNull().maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0.0)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot("下载", semantic.download)
                LegendDot("上传", semantic.upload)
            }
            Text(
                text = if (hasValid) "峰值 ${formatBytes(peak)}/s" else emptyText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                for (i in 0..3) {
                    val y = h * i / 3f
                    drawLine(gridColor.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                if (!hasValid) return@Canvas
                // 分别绘制下载/上传曲线与渐变填充，null 样本处严格断开。
                // Draw download/upload paths with area fill while breaking segments on null gaps.
                for (isUpload in listOf(false, true)) {
                    val strokeColor = if (isUpload) semantic.upload else semantic.download
                    val linePath = Path()
                    val segments = ArrayList<MutableList<Offset>>()
                    samples.forEachIndexed { index, rate ->
                        if (rate == null) {
                            if (segments.lastOrNull()?.isNotEmpty() == true) segments.add(ArrayList())
                            return@forEachIndexed
                        }
                        val x = w * (59 - samples.size + 1 + index).coerceIn(0, 59) / 59f
                        val value = if (isUpload) rate.tx else rate.rx
                        val y = h - (value / peak * h).toFloat().coerceIn(0f, h)
                        val pt = Offset(x, y)
                        if (segments.isEmpty()) segments.add(ArrayList())
                        segments.last().add(pt)
                    }
                    for (seg in segments) {
                        if (seg.isEmpty()) continue
                        if (seg.size > 1) {
                            val area = Path().apply {
                                moveTo(seg.first().x, h)
                                seg.forEach { lineTo(it.x, it.y) }
                                lineTo(seg.last().x, h)
                                close()
                            }
                            drawPath(
                                path = area,
                                brush = Brush.verticalGradient(
                                    listOf(strokeColor.copy(alpha = 0.24f), strokeColor.copy(alpha = 0.0f))
                                )
                            )
                        }
                        linePath.reset()
                        linePath.moveTo(seg.first().x, seg.first().y)
                        for (i in 1 until seg.size) linePath.lineTo(seg[i].x, seg[i].y)
                        if (seg.size > 1) {
                            drawPath(
                                path = linePath,
                                color = strokeColor,
                                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        drawCircle(strokeColor, radius = 3.2.dp.toPx(), center = seg.last())
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionSparkline(
    samples: List<Rate?>,
    modifier: Modifier = Modifier
) {
    val semantic = LocalWieslerColors.current
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val valid = samples.any { it != null }
    if (!valid) {
        Text(
            text = "等待速率样本",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    val peak = maxOf(1.0, samples.filterNotNull().maxOfOrNull { maxOf(it.rx, it.tx) } ?: 1.0)
    Canvas(modifier = modifier.height(34.dp)) {
        val w = size.width
        val h = size.height
        val bottom = h - 3.dp.toPx()
        drawLine(baseline.copy(alpha = 0.6f), Offset(0f, bottom), Offset(w, bottom), strokeWidth = 1.dp.toPx())
        for (isUpload in listOf(false, true)) {
            val color = if (isUpload) semantic.upload else semantic.download
            val path = Path()
            var connected = false
            var last: Offset? = null
            samples.forEachIndexed { idx, rate ->
                if (rate == null) {
                    connected = false
                    return@forEachIndexed
                }
                val denom = maxOf(1, samples.size - 1)
                val x = w * idx / denom.toFloat()
                val v = if (isUpload) rate.tx else rate.rx
                val y = bottom - (v / peak * (bottom - 4.dp.toPx())).toFloat().coerceIn(0f, bottom)
                if (connected) path.lineTo(x, y) else path.moveTo(x, y)
                connected = true
                last = Offset(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
            last?.let { drawCircle(color, radius = 2.4.dp.toPx(), center = it) }
        }
    }
}

@Composable
fun RoutePathStrip(
    nodes: List<RouteNode>,
    modifier: Modifier = Modifier
) {
    val semantic = LocalWieslerColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Router,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        nodes.forEachIndexed { idx, node ->
            val dotColor = when (node.kind) {
                RouteNodeKind.DEVICE -> semantic.upload
                RouteNodeKind.ROUTE -> semantic.download
                RouteNodeKind.UNKNOWN -> semantic.warning
                RouteNodeKind.SERVER -> MaterialTheme.colorScheme.primary
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, dotColor.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = node.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (idx < nodes.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.5.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MetricChipTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    compact: Boolean = false
) {
    val animatedAccent by animateColorAsState(targetValue = accentColor, label = "MetricAccent")
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 12.dp else 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                    .copy(fontWeight = FontWeight.Bold),
                color = animatedAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
