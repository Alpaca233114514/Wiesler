package io.wiesler.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wiesler.android.Rate
import io.wiesler.android.RouteNode
import io.wiesler.android.RouteNodeKind
import io.wiesler.android.formatBytes
import kotlin.math.roundToInt

@Composable
fun wieslerPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onBackground,
    contentColor = MaterialTheme.colorScheme.background,
    disabledContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f),
    disabledContentColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f)
)

@Composable
fun wieslerSecondaryButtonColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
fun ExpressiveSectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    contentSpacing: Int = 16,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        border = BorderStroke(
            0.5.dp,
            if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.07f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing.dp),
            content = content
        )
    }
}

@Composable
fun ExpressivePageHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

// 极简文字状态指示：去除非交互圆角胶囊壳，用状态点与排印层级表达语义。
// Minimal typographic status indicator: conveys semantic state with dot + type instead of decorative pills.
@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

data class StatMetricItem(
    val label: String,
    val value: String,
    val accent: Color? = null
)

// 无边框发丝分隔统计条：替代卡片内套灰色小卡片，对齐桌面端与现代编辑排版。
// Borderless hairline-separated stat strip replacing nested gray metric cards.
@Composable
fun MetricStatStrip(
    items: List<StatMetricItem>,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val defaultInk = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        items.forEachIndexed { idx, item ->
            val targetColor = item.accent ?: defaultInk
            val animatedAccent by animateColorAsState(targetValue = targetColor, label = "StatAccent$idx")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (idx == 0) 0.dp else 14.dp,
                        end = if (idx == items.lastIndex) 0.dp else 14.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.value,
                    style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                        .copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                    color = animatedAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (idx < items.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
fun MinimalLinearBar(
    progress: Float,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
    }
}

// 极细时间轴拖动条，替代 M3 Expressive 粗壮胶囊 Slider。
// Hairline timeline scrubber replacing the bulky M3 Expressive Slider.
@Composable
fun MinimalScrubber(
    value: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMax = maxValue.coerceAtLeast(1)
    val fraction = (value.coerceIn(0, safeMax).toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.onBackground
    val thumbColor = MaterialTheme.colorScheme.onBackground
    val thumbBorder = MaterialTheme.colorScheme.surfaceContainerLow
    val latestMax by rememberUpdatedState(safeMax)
    val latestOnChange by rememberUpdatedState(onValueChange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun emit(x: Float) {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val ratio = (x / width).coerceIn(0f, 1f)
                        latestOnChange((ratio * latestMax).roundToInt())
                    }
                    emit(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        emit(change.position.x)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            val trackH = 3.dp.toPx()
            val cy = size.height / 2f
            val r = 6.5.dp.toPx()
            val usableW = (size.width - r * 2f).coerceAtLeast(1f)
            val thumbX = r + usableW * fraction

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(r, cy - trackH / 2f),
                size = Size(usableW, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
            )
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(r, cy - trackH / 2f),
                size = Size(usableW * fraction, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
            )
            drawCircle(color = thumbBorder, radius = r + 1.5.dp.toPx(), center = Offset(thumbX, cy))
            drawCircle(color = thumbColor, radius = r, center = Offset(thumbX, cy))
        }
    }
}

@Composable
fun <T> ExpressiveConnectedChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false
) {
    val trackShape = RoundedCornerShape(12.dp)
    val itemShape = RoundedCornerShape(9.dp)
    val isDark = LocalIsDarkTheme.current
    val activeBg = if (isDark) Color(0xFF2C2C32) else Color.White
    val activeBorder = if (isDark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.08f)

    val rowModifier = if (scrollable) {
        modifier
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp)
    } else {
        modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
    }

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            Box(
                modifier = (if (scrollable) Modifier else Modifier.weight(1f))
                    .clip(itemShape)
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(activeBg)
                                .border(0.5.dp, activeBorder, itemShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { if (!isSelected) onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
    val sameExit = egress.system != null && egress.proxy != null && egress.system.ip == egress.proxy.ip
    val splitExit = egress.system != null && egress.proxy != null && egress.system.ip != egress.proxy.ip

    ExpressiveSectionCard(
        modifier = modifier,
        contentSpacing = 14
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("公网出口", style = MaterialTheme.typography.titleLarge)
                if (sameExit) {
                    StatusPill(
                        text = "同出口",
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                } else if (splitExit) {
                    StatusPill(
                        text = "已分流",
                        containerColor = Color.Transparent,
                        contentColor = semantic.success,
                        showDot = true
                    )
                }
            }
            if (egress.busy) {
                LoadingIndicator(modifier = Modifier.size(26.dp))
            } else {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "刷新公网出口",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EgressRouteColumn(
                title = "系统",
                ip = egress.system?.ip ?: "—",
                location = egress.system?.location?.summary()
                    ?: egress.system?.locationError
                    ?: egress.systemError
                    ?: "未探测",
                accent = semantic.upload,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            EgressRouteColumn(
                title = "代理",
                ip = egress.proxy?.ip ?: "—",
                location = egress.proxy?.location?.summary()
                    ?: egress.proxy?.locationError
                    ?: egress.proxyError
                    ?: if (controllerEnabled) "未探测" else "未连接",
                accent = semantic.download,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EgressRouteColumn(
    title: String,
    ip: String,
    location: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = accent)
        Text(
            text = ip,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = location,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DualSeriesAreaChart(
    samples: List<Rate?>,
    modifier: Modifier = Modifier,
    emptyText: String = "等待采样"
) {
    val semantic = LocalWieslerColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val hasValid = samples.any { it != null }
    val peak = maxOf(1024.0, samples.filterNotNull().maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0.0)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(gridColor.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), strokeWidth = 0.8.dp.toPx())
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
                                    listOf(strokeColor.copy(alpha = 0.16f), strokeColor.copy(alpha = 0.0f))
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
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        drawCircle(strokeColor, radius = 2.6.dp.toPx(), center = seg.last())
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
        Box(modifier = modifier.height(28.dp), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(baseline.copy(alpha = 0.45f))
            )
        }
        return
    }
    val peak = maxOf(1.0, samples.filterNotNull().maxOfOrNull { maxOf(it.rx, it.tx) } ?: 1.0)
    Canvas(modifier = modifier.height(30.dp)) {
        val w = size.width
        val h = size.height
        val bottom = h - 2.dp.toPx()
        drawLine(baseline.copy(alpha = 0.5f), Offset(0f, bottom), Offset(w, bottom), strokeWidth = 0.8.dp.toPx())
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
                val y = bottom - (v / peak * (bottom - 3.dp.toPx())).toFloat().coerceIn(0f, bottom)
                if (connected) path.lineTo(x, y) else path.moveTo(x, y)
                connected = true
                last = Offset(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
            last?.let { drawCircle(color, radius = 2.2.dp.toPx(), center = it) }
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
        nodes.forEachIndexed { idx, node ->
            val dotColor = when (node.kind) {
                RouteNodeKind.DEVICE -> semantic.upload
                RouteNodeKind.ROUTE -> semantic.download
                RouteNodeKind.UNKNOWN -> semantic.warning
                RouteNodeKind.SERVER -> MaterialTheme.colorScheme.primary
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = node.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (idx < nodes.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(1.dp)
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
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
