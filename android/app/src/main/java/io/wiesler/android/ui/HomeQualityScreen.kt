package io.wiesler.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_PING_TARGETS = listOf(
    "223.5.5.5" to "阿里 223.5",
    "1.1.1.1" to "CF 1.1.1.1",
    "8.8.8.8" to "G 8.8.8.8"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
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
    val bottomClearance = LocalBottomBarClearance.current
    val gradeAccent = gradeColor(quality.grade, semantic)
    val animatedAccent by animateColorAsState(targetValue = gradeAccent, label = "QualityAccent")
    val continuousActive = state.packetLoss.running
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "home_header") {
            ExpressivePageHeader(
                kicker = "NETWORK QUALITY",
                title = "网络质量监测",
                subtitle = buildString {
                    append(state.networkSummary)
                    if (quality.evaluatedAtMs > 0L) {
                        append(" · 更新 ")
                        append(timeFmt.format(Date(quality.evaluatedAtMs)))
                    }
                },
                trailing = {
                    StatusPill(
                        text = if (link.connected) {
                            if (link.validated) "联网已校验" else "校验未通过"
                        } else {
                            "离线"
                        },
                        containerColor = when {
                            !link.connected -> semantic.danger.copy(alpha = 0.16f)
                            link.validated -> semantic.success.copy(alpha = 0.16f)
                            else -> semantic.warning.copy(alpha = 0.16f)
                        },
                        contentColor = when {
                            !link.connected -> semantic.danger
                            link.validated -> semantic.success
                            else -> semantic.warning
                        }
                    )
                }
            )
        }

        // 综合评分仪表盘 Hero 卡 / Composite Score Hero Card
        item(key = "home_hero_score") {
            ExpressiveSectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QualityArcGauge(
                        score = quality.overallScore,
                        grade = quality.grade,
                        accent = animatedAccent,
                        coverageText = "${quality.measuredDimensions}/${quality.totalApplicableDimensions} 维已测",
                        modifier = Modifier.size(134.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StatusPill(
                                text = "${quality.grade.label} · ${quality.grade.badge}",
                                containerColor = animatedAccent.copy(alpha = 0.18f),
                                contentColor = animatedAccent
                            )
                            if (continuousActive) {
                                StatusPill(
                                    text = "实时采样",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text(
                            text = quality.headline,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = quality.summary,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "↓${formatRate(state.globalRate?.rx)} · ↑${formatRate(state.globalRate?.tx)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = semantic.download,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = quality.advice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRunQualityCheck,
                        enabled = !state.qualityCheckBusy,
                        modifier = Modifier.weight(1.15f)
                    ) {
                        if (state.qualityCheckBusy) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("体检中…")
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键综合体检")
                        }
                    }
                    FilledTonalButton(
                        onClick = { onToggleContinuousMonitor(!continuousActive) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (continuousActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (continuousActive) "停止监测" else "持续监测")
                    }
                }

                // 快捷 ICMP 目标单行等宽切换 / Single-row balanced ICMP target switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "目标",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    QUICK_PING_TARGETS.forEach { (ip, label) ->
                        val selected = state.packetLoss.target == ip
                        Surface(
                            shape = CircleShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .clickable { onSelectPingTarget(ip) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 四维分项评分 2×2 卡片 / 4-Dimension Breakdown Grid
        item(key = "home_dimensions") {
            val dims = quality.dimensions
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("分项指标评分", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = "点击可深入查看",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                dims.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { dim ->
                            DimensionScoreCard(
                                dimension = dim,
                                isCellularAccess = link.isCellular,
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
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // 综合评分与延迟实时走势 / Live Quality Score & RTT Trend Chart
        item(key = "home_trend") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("质量评分与延迟走势", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = buildString {
                                append("最近 ${quality.history.size} / 60 次采样")
                                quality.jitterMs?.let { append(String.format(Locale.ROOT, " · 抖动 %.1f ms", it)) }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

        // 场景适配评估 / Scenario Suitability Assessment
        item(key = "home_scenarios") {
            ExpressiveSectionCard {
                Text("业务场景适配评估", style = MaterialTheme.typography.titleLarge)
                quality.scenarios.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { sc ->
                            ScenarioCard(
                                scenario = sc,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // 当前链路环境摘要与快捷入口 / Link Environment Summary & Quick Jumps
        item(key = "home_environment_summary") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前链路环境", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = link.interfaceName ?: "默认接口",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val envRows = buildList {
                    add("接入方式" to buildString {
                        append(link.transportLabel)
                        if (link.isVpn) append(" · VPN 隧道")
                        if (link.notMetered) append(" · 不计流量")
                    })
                    link.downstreamKbps?.takeIf { it > 0 }?.let { down ->
                        val up = link.upstreamKbps?.takeIf { it > 0 }?.let { NetworkQualityEngine.formatBandwidth(it) } ?: "—"
                        add("协商带宽" to "↓ ${NetworkQualityEngine.formatBandwidth(down)} / ↑ $up")
                    }
                    add("DNS 配置" to buildString {
                        if (link.privateDnsActive) {
                            append("Private DNS (${link.privateDnsServer ?: "自动"})")
                        } else {
                            append(link.dnsServers.take(2).joinToString(", ").ifBlank { "系统默认" })
                        }
                    })
                    add("系统公网出口" to (state.egress.system?.let {
                        "${it.ip} · ${it.location?.summary() ?: "归属地未知"}"
                    } ?: state.egress.systemError ?: "点击一键体检探测"))
                    if (state.controllerEnabled || state.egress.proxy != null) {
                        add("代理公网出口" to (state.egress.proxy?.let {
                            "${it.ip} · ${it.location?.summary() ?: "归属地未知"}"
                        } ?: state.egress.proxyError ?: "控制器已启用，待探测"))
                    }
                }

                envRows.forEachIndexed { idx, (k, v) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = k,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = v,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (idx < envRows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onNavigateTab(WieslerTab.MONITOR) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.MonitorHeart, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("详细监测", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onNavigateTab(WieslerTab.GRAPH) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("流量图谱", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onNavigateTab(WieslerTab.PRIVACY) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("隐私体检", maxLines = 1)
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
    coverageText: String,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val targetSweep = ((score ?: 0) / 100f).coerceIn(0f, 1f) * 250f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "QualityArcSweep"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val strokePx = 11.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            // 250° 开放式仪表盘轨道 / 250-degree open radial gauge track.
            drawArc(
                color = trackColor,
                startAngle = 145f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            if (animatedSweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(accent.copy(alpha = 0.55f), accent, accent)
                    ),
                    startAngle = 145f,
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
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 36.sp
                ),
                color = if (grade == NetworkQualityGrade.OFFLINE) MaterialTheme.colorScheme.onSurfaceVariant else accent
            )
            Text(
                text = coverageText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DimensionScoreCard(
    dimension: QualityDimensionScore,
    isCellularAccess: Boolean,
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
    val icon: ImageVector = when (dimension.id) {
        QualityDimensionId.ACCESS_SIGNAL -> if (isCellularAccess) Icons.Rounded.CellTower else Icons.Rounded.Wifi
        QualityDimensionId.LATENCY_STABILITY -> Icons.Rounded.NetworkCheck
        QualityDimensionId.REACHABILITY_DNS -> Icons.Rounded.Dns
        QualityDimensionId.PROXY_HEALTH -> Icons.Rounded.Router
    }

    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (dimension.available && (dimension.score ?: 100) < 55) accent.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    Text(
                        text = dimension.id.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = dimension.score?.toString() ?: "—",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = accent
                )
                Text(
                    text = dimension.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 细长分数进度条 / Slim dimension score bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (dimension.available && dimension.score != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((dimension.score / 100f).coerceIn(0.04f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(accent)
                    )
                }
            }

            Text(
                text = dimension.primaryMetric,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = dimension.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScenarioCard(
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
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = scenario.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = scenario.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = scenario.detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
            .height(112.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (points.isEmpty()) {
            Text(
                text = "等待连续采样写入质量曲线…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(gridColor.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                val denom = maxOf(1, points.size - 1)

                // 综合评分主曲线 (0..100) / Overall quality score area line (0..100).
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
                        brush = Brush.verticalGradient(listOf(scoreColor.copy(alpha = 0.24f), Color.Transparent))
                    )
                    val line = Path().apply {
                        moveTo(scorePts.first().x, scorePts.first().y)
                        for (i in 1 until scorePts.size) lineTo(scorePts[i].x, scorePts[i].y)
                    }
                    drawPath(
                        path = line,
                        color = scoreColor,
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                drawCircle(scoreColor, radius = 3.2.dp.toPx(), center = scorePts.last())

                // 叠加 RTT 折线（按当前窗口峰值 1.25 倍留顶边距归一化，最低 150ms 标尺）。
                // Overlay RTT line scaled with 25% headroom (min 150ms scale) so peaks stay inside frame.
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
                    lastRttPt?.let { drawCircle(rttColor, radius = 2.4.dp.toPx(), center = it) }
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
