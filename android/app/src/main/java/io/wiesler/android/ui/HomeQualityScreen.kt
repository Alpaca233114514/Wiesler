package io.wiesler.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wiesler.android.NetworkQualityEngine
import io.wiesler.android.NetworkQualityGrade
import io.wiesler.android.NetworkQualityHistoryPoint
import io.wiesler.android.QualityDimensionId
import io.wiesler.android.QualityDimensionScore
import io.wiesler.android.ScenarioAssessment
import io.wiesler.android.ScenarioStatus
import io.wiesler.android.formatRate

private val QUICK_PING_TARGETS = listOf(
    "223.5.5.5" to "阿里 DNS",
    "1.1.1.1" to "Cloudflare",
    "8.8.8.8" to "Google"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeQualityScreen(
    state: WieslerUiState,
    onRunQualityCheck: () -> Unit,
    onToggleContinuousMonitor: (Boolean) -> Unit,
    onSelectPingTarget: (String) -> Unit,
    onOpenControllerDialog: () -> Unit,
    onNavigateTab: (WieslerTab) -> Unit
) {
    val semantic = LocalWieslerColors.current
    val quality = state.qualitySnapshot
    val link = state.systemLink
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current
    val gradeAccent = gradeColor(quality.grade, semantic)
    val animatedAccent by animateColorAsState(targetValue = gradeAccent, label = "QualityAccent")
    val continuousActive = state.packetLoss.running

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
        item(key = "home_header") {
            ExpressivePageHeader(
                title = "网络质量",
                trailing = {
                    val (statusText, statusColor) = when {
                        !link.connected -> "离线" to semantic.danger
                        link.validated -> link.transportLabel to semantic.success
                        else -> "未校验" to semantic.warning
                    }
                    StatusPill(
                        text = statusText,
                        containerColor = Color.Transparent,
                        contentColor = statusColor,
                        showDot = true
                    )
                }
            )
        }

        // 综合评分主卡：单主 CTA + 发丝次按钮 + 无截断双行等宽数字速率。
        // Hero card: single primary CTA, restrained hairline secondary control, non-truncating tabular rates.
        item(key = "home_hero_score") {
            ExpressiveSectionCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentSpacing = 18
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QualityArcGauge(
                        score = quality.overallScore,
                        grade = quality.grade,
                        accent = animatedAccent,
                        modifier = Modifier.size(118.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = quality.headline,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "↓ ${formatRate(state.globalRate?.rx)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = semantic.download,
                                maxLines = 1
                            )
                            Text(
                                text = "↑ ${formatRate(state.globalRate?.tx)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (quality.overallScore != null && quality.overallScore < 70) {
                            Text(
                                text = quality.advice,
                                style = MaterialTheme.typography.labelMedium,
                                color = animatedAccent,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRunQualityCheck,
                        enabled = !state.qualityCheckBusy,
                        colors = wieslerPrimaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        if (state.qualityCheckBusy) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("体检中…")
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键体检")
                        }
                    }
                    OutlinedButton(
                        onClick = { onToggleContinuousMonitor(!continuousActive) },
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (continuousActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (continuousActive) "停止监测" else "持续监测")
                    }
                }

                ExpressiveConnectedChoiceRow(
                    options = QUICK_PING_TARGETS,
                    selected = state.packetLoss.target,
                    onSelect = onSelectPingTarget
                )
            }
        }

        // 四维分项评分：0.5dp 发丝 2×2 网格，彻底去除卡中套卡。
        // 4-Dimension Breakdown: flat 0.5dp hairline 2x2 grid with zero nested card boxes.
        item(key = "home_dimensions") {
            val dims = quality.dimensions
            ExpressiveSectionCard(
                contentPadding = PaddingValues(0.dp),
                contentSpacing = 0
            ) {
                Text(
                    text = "分项评分",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                val rows = dims.chunked(2)
                rows.forEachIndexed { rowIdx, pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                    ) {
                        pair.forEachIndexed { colIdx, dim ->
                            DimensionScoreCell(
                                dimension = dim,
                                onClick = {
                                    when (dim.id) {
                                        QualityDimensionId.ACCESS_SIGNAL,
                                        QualityDimensionId.LATENCY_STABILITY -> onNavigateTab(WieslerTab.MONITOR)
                                        QualityDimensionId.REACHABILITY_DNS -> onNavigateTab(WieslerTab.PRIVACY)
                                        QualityDimensionId.PROXY_HEALTH -> {
                                            if (state.controllerEnabled) onNavigateTab(WieslerTab.GRAPH)
                                            else onOpenControllerDialog()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            if (colIdx == 0) {
                                Box(
                                    modifier = Modifier
                                        .width(0.5.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    if (rowIdx < rows.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        // 评分与延迟走势 / Live Quality Score & RTT Trend
        item(key = "home_trend") {
            ExpressiveSectionCard(contentSpacing = 12) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("评分与延迟", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendDot("评分", animatedAccent)
                        LegendDot("RTT", semantic.upload)
                    }
                }
                QualityHistoryChart(
                    points = quality.history,
                    scoreColor = animatedAccent,
                    rttColor = semantic.upload
                )
            }
        }

        // 场景适配：0.5dp 发丝 2×2 网格 / Scenario Suitability in flat hairline 2x2 grid
        item(key = "home_scenarios") {
            val rows = quality.scenarios.chunked(2)
            ExpressiveSectionCard(
                contentPadding = PaddingValues(0.dp),
                contentSpacing = 0
            ) {
                Text(
                    text = "场景适配",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                rows.forEachIndexed { rowIdx, pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                    ) {
                        pair.forEachIndexed { colIdx, sc ->
                            ScenarioCell(
                                scenario = sc,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                            if (colIdx == 0) {
                                Box(
                                    modifier = Modifier
                                        .width(0.5.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    if (rowIdx < rows.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        // 当前链路 / Link Environment Summary
        item(key = "home_environment_summary") {
            ExpressiveSectionCard(contentSpacing = 12) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前链路", style = MaterialTheme.typography.titleLarge)
                    link.interfaceName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val envRows = buildList {
                    add("接入" to buildString {
                        append(link.transportLabel)
                        if (link.isVpn) append(" · VPN")
                    })
                    link.downstreamKbps?.takeIf { it > 0 }?.let { down ->
                        val up = link.upstreamKbps?.takeIf { it > 0 }?.let { NetworkQualityEngine.formatBandwidth(it) } ?: "—"
                        add("带宽" to "↓ ${NetworkQualityEngine.formatBandwidth(down)} / ↑ $up")
                    }
                    add("DNS" to if (link.privateDnsActive) {
                        link.privateDnsServer ?: "Private DNS"
                    } else {
                        link.dnsServers.take(2).joinToString(", ").ifBlank { "系统默认" }
                    })
                    state.egress.system?.let {
                        add("系统出口" to it.ip)
                    }
                    state.egress.proxy?.let {
                        add("代理出口" to it.ip)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    envRows.forEachIndexed { idx, (k, v) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = v,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (idx < envRows.lastIndex) {
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

@Composable
private fun QualityArcGauge(
    score: Int?,
    grade: NetworkQualityGrade,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val targetSweep = ((score ?: 0) / 100f).coerceIn(0f, 1f) * 240f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessLow),
        label = "QualityArcSweep"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val strokePx = 7.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            if (animatedSweep > 0.5f) {
                drawArc(
                    color = accent,
                    startAngle = 150f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score?.toString() ?: "—",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    fontSize = 34.sp
                ),
                color = if (grade == NetworkQualityGrade.OFFLINE) MaterialTheme.colorScheme.onSurfaceVariant else accent
            )
            Text(
                text = grade.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DimensionScoreCell(
    dimension: QualityDimensionScore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalWieslerColors.current
    val accent = when {
        !dimension.available || dimension.score == null -> MaterialTheme.colorScheme.onSurfaceVariant
        dimension.score >= 80 -> semantic.success
        dimension.score >= 55 -> semantic.warning
        else -> semantic.danger
    }

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dimension.id.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp)
            )
        }

        Text(
            text = dimension.score?.toString() ?: "—",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = accent
        )

        MinimalLinearBar(
            progress = if (dimension.available && dimension.score != null) {
                (dimension.score / 100f).coerceIn(0.04f, 1f)
            } else 0f,
            color = accent
        )

        Text(
            text = dimension.primaryMetric,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScenarioCell(
    scenario: ScenarioAssessment,
    modifier: Modifier = Modifier
) {
    val semantic = LocalWieslerColors.current
    val accent = when (scenario.status) {
        ScenarioStatus.OPTIMAL -> semantic.success
        ScenarioStatus.USABLE -> semantic.download
        ScenarioStatus.DEGRADED -> semantic.danger
        ScenarioStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = scenario.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        StatusPill(
            text = scenario.status.label,
            containerColor = Color.Transparent,
            contentColor = accent,
            showDot = true
        )
    }
}

@Composable
private fun QualityHistoryChart(
    points: List<NetworkQualityHistoryPoint>,
    scoreColor: Color,
    rttColor: Color
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (points.isEmpty()) {
            Text(
                text = "等待采样",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(gridColor.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), strokeWidth = 0.8.dp.toPx())
                }
                val denom = maxOf(1, points.size - 1)

                val scorePts = points.mapIndexed { idx, pt ->
                    val x = if (points.size == 1) w else w * idx / denom.toFloat()
                    val y = h - (pt.score / 100f).coerceIn(0f, 1f) * h
                    Offset(x, y)
                }
                if (scorePts.size > 1) {
                    val area = Path().apply {
                        moveTo(scorePts.first().x, h)
                        scorePts.forEach { lineTo(it.x, it.y) }
                        lineTo(scorePts.last().x, h)
                        close()
                    }
                    drawPath(
                        path = area,
                        brush = Brush.verticalGradient(listOf(scoreColor.copy(alpha = 0.16f), Color.Transparent))
                    )
                    val line = Path().apply {
                        moveTo(scorePts.first().x, scorePts.first().y)
                        for (i in 1 until scorePts.size) lineTo(scorePts[i].x, scorePts[i].y)
                    }
                    drawPath(
                        path = line,
                        color = scoreColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                drawCircle(scoreColor, radius = 2.6.dp.toPx(), center = scorePts.last())

                val peakRtt = points.mapNotNull { it.rttMs }.maxOrNull() ?: 0.0
                val maxRtt = maxOf(150.0, peakRtt * 1.25)
                val rttPath = Path()
                var rttStarted = false
                var lastRttPt: Offset? = null
                points.forEachIndexed { idx, pt ->
                    val rtt = pt.rttMs ?: return@forEachIndexed
                    val x = if (points.size == 1) w else w * idx / denom.toFloat()
                    val y = h - (rtt / maxRtt).toFloat().coerceIn(0f, 1f) * h
                    val offset = Offset(x, y)
                    if (!rttStarted) {
                        rttPath.moveTo(x, y)
                        rttStarted = true
                    } else {
                        rttPath.lineTo(x, y)
                    }
                    lastRttPt = offset
                }
                if (rttStarted) {
                    drawPath(
                        path = rttPath,
                        color = rttColor.copy(alpha = 0.72f),
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    lastRttPt?.let { drawCircle(rttColor, radius = 2.2.dp.toPx(), center = it) }
                }
            }
        }
    }
}

private fun gradeColor(grade: NetworkQualityGrade, semantic: WieslerSemanticColors): Color = when (grade) {
    NetworkQualityGrade.EXCELLENT -> semantic.success
    NetworkQualityGrade.GOOD -> semantic.download
    NetworkQualityGrade.FAIR -> semantic.warning
    NetworkQualityGrade.POOR,
    NetworkQualityGrade.CRITICAL,
    NetworkQualityGrade.OFFLINE -> semantic.danger
}
