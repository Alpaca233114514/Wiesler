package io.wiesler.android.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wiesler.android.FlowRouting
import io.wiesler.android.StudioFlow
import io.wiesler.android.StudioHostGroup
import io.wiesler.android.StudioSourceFilter
import io.wiesler.android.formatBytes
import io.wiesler.android.formatRate
import io.wiesler.android.routePath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.min

private data class GraphNodeBox(
    val key: String,
    val title: String,
    val subtitle: String,
    val isApp: Boolean,
    val stackCount: Int,
    val leader: StudioFlow,
    val warning: Boolean,
    val rect: Rect
)

private data class PulseEdge(
    val start: Offset,
    val end: Offset,
    val hasRx: Boolean,
    val hasTx: Boolean
)

private class GraphHitState {
    var nodes: List<GraphNodeBox> = emptyList()
    var edges: List<Triple<StudioFlow, Offset, Offset>> = emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphWorkbenchScreen(
    state: WieslerUiState,
    onSwitchSource: (Boolean) -> Unit,
    onToggleFreeze: () -> Unit,
    onToggleCapturePause: () -> Unit,
    onReplayIndex: (Int) -> Unit,
    onBackToNow: () -> Unit,
    onQueryChange: (String) -> Unit,
    onProtocolChange: (String) -> Unit,
    onSourceFilterChange: (StudioSourceFilter) -> Unit,
    onSelectFlow: (String?) -> Unit,
    onOpenControllerDialog: () -> Unit,
    onRefreshEgress: () -> Unit
) {
    var displayLimit by remember { mutableIntStateOf(30) }
    var inspectFlowId by remember { mutableStateOf<String?>(null) }
    var showGraphInfo by remember { mutableStateOf(false) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val customPositions = remember { mutableStateMapOf<String, Offset>() }

    val semantic = LocalWieslerColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
    val selectedFlow = state.studioFilteredFlows.firstOrNull { it.id == (inspectFlowId ?: state.studioSelectedId) }
    val shownFlows = remember(state.studioFilteredFlows, displayLimit) {
        state.studioFilteredFlows.take(displayLimit)
    }

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
            ExpressivePageHeader(
                title = "连接图谱",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onToggleCapturePause) {
                            Icon(
                                imageVector = if (state.capturePaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (state.capturePaused) "恢复采集" else "暂停采集",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onOpenControllerDialog) {
                            Icon(
                                Icons.Rounded.Tune,
                                contentDescription = "配置控制器",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }

        item(key = "source_mode") {
            ExpressiveConnectedChoiceRow(
                options = listOf(true to "实时连接", false to "本地演示"),
                selected = state.studioLive,
                onSelect = {
                    displayLimit = 30
                    onSwitchSource(it)
                }
            )
        }

        item(key = "graph_card") {
            ExpressiveSectionCard(contentSpacing = 12) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("拓扑", style = MaterialTheme.typography.titleLarge)
                        val drawnCount = state.studioHostGroups.sumOf { it.flows.size }
                        Text(
                            text = "$drawnCount/${state.studioFilteredFlows.size}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = onToggleFreeze,
                            enabled = state.studioCurrentFrame != null,
                            colors = wieslerSecondaryButtonColors(),
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AcUnit,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (state.studioFrozen) "继续" else "冻结")
                        }
                        IconButton(
                            onClick = { showGraphInfo = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = "说明",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                InteractiveHostGraphCanvas(
                    groups = state.studioHostGroups,
                    selectedId = state.studioSelectedId,
                    animatePulses = state.preferences.pulseAnimation && !state.studioFrozen && !state.capturePaused,
                    zoom = zoom,
                    pan = pan,
                    customPositions = customPositions,
                    onZoomChange = { zoom = it },
                    onPanChange = { pan = it },
                    emptyMessage = state.studioCurrentFrame?.error ?: when {
                        state.studioCurrentFrame == null && state.studioLive -> "未连接控制器"
                        state.studioCurrentFrame?.flows?.isNotEmpty() == true -> "无匹配会话"
                        else -> "暂无活跃连接"
                    },
                    onSelectFlow = { flow ->
                        onSelectFlow(flow.id)
                        inspectFlowId = flow.id
                    }
                )

                // 图例、缩放按钮与时间同行对齐，画布内零悬浮遮挡。
                // Legend, zoom icons, and timestamp aligned on one clean footer row with zero canvas overlap.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot("上传", semantic.upload)
                        LegendDot("下载", semantic.download)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { zoom = (zoom * 0.85f).coerceIn(0.65f, 2.2f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "缩小", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { zoom = 1f; pan = Offset.Zero; customPositions.clear() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "适配", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { zoom = (zoom * 1.2f).coerceIn(0.65f, 2.2f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "放大", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = state.studioCurrentFrame?.let {
                                (if (state.studioFrozen) "回放 " else "") + timeFormat.format(Date(it.wallTime))
                            } ?: "—",
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.studioFrameCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val maxIndex = (state.studioFrameCount - 1).coerceAtLeast(1)
                        MinimalScrubber(
                            value = state.studioFrameIndex.coerceIn(0, maxIndex),
                            maxValue = maxIndex,
                            onValueChange = onReplayIndex,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.studioFrozen) {
                            TextButton(
                                onClick = onBackToNow,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("现在")
                            }
                        }
                    }
                }
            }
        }

        item(key = "egress") {
            EgressComparisonBanner(
                egress = state.egress,
                controllerEnabled = state.controllerEnabled,
                onRefresh = onRefreshEgress
            )
        }

        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("会话", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${state.studioFilteredFlows.size}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = state.studioQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜索应用、域名、IP 或规则") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.studioQuery.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                ExpressiveConnectedChoiceRow(
                    options = listOf("全部" to "全部", "TCP" to "TCP", "UDP" to "UDP"),
                    selected = state.studioProtocol,
                    onSelect = onProtocolChange
                )
                ExpressiveConnectedChoiceRow(
                    options = StudioSourceFilter.entries.map { it to it.label },
                    selected = state.studioSourceFilter,
                    onSelect = onSourceFilterChange,
                    scrollable = true
                )
            }
        }

        if (shownFlows.isEmpty()) {
            item(key = "empty_flows") {
                ExpressiveSectionCard {
                    Text(
                        text = if (state.studioCurrentFrame == null && state.studioLive) {
                            "前往设置连接 Mihomo 控制器后查看实时会话"
                        } else {
                            "无匹配会话"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(shownFlows, key = { it.id }) { flow ->
                SessionFlowCard(
                    flow = flow,
                    selected = flow.id == state.studioSelectedId,
                    sparkline = state.studioRateHistory[flow.flowKey].orEmpty(),
                    onClick = {
                        onSelectFlow(flow.id)
                        inspectFlowId = flow.id
                    }
                )
            }
            if (state.studioFilteredFlows.size > shownFlows.size && displayLimit < 200) {
                item(key = "load_more") {
                    FilledTonalButton(
                        onClick = { displayLimit = (displayLimit + 30).coerceAtMost(200) },
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("加载更多 (${shownFlows.size}/${state.studioFilteredFlows.size})")
                    }
                }
            }
        }
    }

    val inspected = selectedFlow.takeIf { inspectFlowId != null }
    if (inspected != null) {
        val peers = state.studioFilteredFlows.filter { it.hostKey == inspected.hostKey }
        SessionInspectorSheet(
            flow = inspected,
            peers = peers,
            isLive = state.studioLive,
            onSwitchPeer = { peerId ->
                onSelectFlow(peerId)
                inspectFlowId = peerId
            },
            onDismiss = { inspectFlowId = null }
        )
    }

    if (showGraphInfo) {
        AlertDialog(
            onDismissRequest = { showGraphInfo = false },
            title = { Text("拓扑说明") },
            text = {
                Text(
                    "同域名不同端口自动堆叠为单一目标节点，点击可在检查器内切换连接。\n\n" +
                        "实时模式仅向控制器发送只读 GET；冻结定格画布的同时后台保留最近 60 帧。"
                )
            },
            confirmButton = { TextButton(onClick = { showGraphInfo = false }) { Text("知道了") } }
        )
    }
}

@Composable
private fun InteractiveHostGraphCanvas(
    groups: List<StudioHostGroup>,
    selectedId: String?,
    animatePulses: Boolean,
    zoom: Float,
    pan: Offset,
    customPositions: MutableMap<String, Offset>,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
    emptyMessage: String,
    onSelectFlow: (StudioFlow) -> Unit
) {
    val semantic = LocalWieslerColors.current
    val inkColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    val titleTypeface = remember { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    val titlePaint = remember(titleTypeface) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = titleTypeface }
    }
    val subPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(semantic.graphCanvas)
            .border(0.5.dp, borderColor, MaterialTheme.shapes.medium)
    ) {
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = mutedColor)
            }
        } else {
            // 使用普通引用保存命中几何，严禁在 Canvas.onDraw 内写 mutableStateOf 引发无限重绘。
            // Keep hit-test geometry in a plain holder so Canvas.onDraw never mutates Compose State.
            val hitState = remember { GraphHitState() }
            var pulseEdges by remember { mutableStateOf<List<PulseEdge>>(emptyList()) }
            var draggingKey by remember { mutableStateOf<String?>(null) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(groups, selectedId, zoom, pan) {
                        detectTapGestures { tap ->
                            val world = Offset((tap.x - pan.x) / zoom, (tap.y - pan.y) / zoom)
                            val hitNode = hitState.nodes.lastOrNull { it.rect.contains(world) }
                            if (hitNode != null) {
                                onSelectFlow(hitNode.leader)
                                return@detectTapGestures
                            }
                            var bestFlow: StudioFlow? = null
                            var bestDist = 36f
                            for ((flow, start, end) in hitState.edges) {
                                for (step in 0..16) {
                                    val t = step / 16f
                                    val pt = cubicPoint(start, end, t)
                                    val d = hypot(pt.x - world.x, pt.y - world.y)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestFlow = flow
                                    }
                                }
                            }
                            bestFlow?.let(onSelectFlow)
                        }
                    }
                    .pointerInput(groups, zoom, pan) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val world = Offset((start.x - pan.x) / zoom, (start.y - pan.y) / zoom)
                                draggingKey = hitState.nodes.lastOrNull { it.rect.contains(world) }?.key
                            },
                            onDragEnd = { draggingKey = null },
                            onDragCancel = { draggingKey = null },
                            onDrag = { change, amount ->
                                change.consume()
                                val key = draggingKey
                                if (key != null) {
                                    val currentRect = hitState.nodes.firstOrNull { it.key == key }?.rect
                                    val base = customPositions[key] ?: currentRect?.topLeft ?: Offset.Zero
                                    customPositions[key] = Offset(base.x + amount.x / zoom, base.y + amount.y / zoom)
                                } else {
                                    onPanChange(pan + amount)
                                }
                            }
                        )
                    }
                    .pointerInput(zoom, pan) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            onZoomChange((zoom * zoomChange).coerceIn(0.65f, 2.2f))
                            onPanChange(pan + panChange)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val step = 28.dp.toPx()
                val dotRadius = 0.9.dp.toPx()
                val gridDotColor = semantic.graphGrid.copy(alpha = 0.45f)
                var gx = 0f
                while (gx <= w) {
                    var gy = 0f
                    while (gy <= h) {
                        drawCircle(gridDotColor, dotRadius, Offset(gx, gy))
                        gy += step
                    }
                    gx += step
                }

                val drawn = groups.flatMap { it.flows }
                val allApps = drawn.map { it.app }.distinct()
                val selectedFlow = drawn.firstOrNull { it.id == selectedId }
                val namedApps = if (allApps.size > 6) {
                    val slice = allApps.take(5).toMutableList()
                    if (selectedFlow != null && selectedFlow.app !in slice) slice[4] = selectedFlow.app
                    slice
                } else allApps

                fun appKey(name: String) = if (name in namedApps) "app:$name" else "other-apps"
                val appBuckets = namedApps.map { name ->
                    Triple(appKey(name), name, drawn.filter { it.app == name })
                }.toMutableList()
                if (allApps.size > 6) {
                    appBuckets.add(Triple("other-apps", "其他应用", drawn.filter { it.app !in namedApps }))
                }

                val appW = min(w * 0.34f, 138.dp.toPx())
                val hostW = min(w * 0.45f, 186.dp.toPx())
                val nodeH = 46.dp.toPx()
                val padX = 12.dp.toPx()
                val padY = 18.dp.toPx()
                fun slotY(idx: Int, count: Int): Float =
                    if (count <= 1) (h - nodeH) / 2f else padY + idx * (h - padY * 2 - nodeH) / (count - 1).toFloat()

                val builtNodes = ArrayList<GraphNodeBox>(appBuckets.size + groups.size)
                appBuckets.forEachIndexed { idx, (key, label, members) ->
                    val leader = members.firstOrNull { it.id == selectedId } ?: members.first()
                    val pos = customPositions[key] ?: Offset(padX, slotY(idx, appBuckets.size))
                    builtNodes.add(
                        GraphNodeBox(
                            key = key,
                            title = label,
                            subtitle = "${members.size} 条",
                            isApp = true,
                            stackCount = 1,
                            leader = leader,
                            warning = false,
                            rect = Rect(pos, Size(appW, nodeH))
                        )
                    )
                }
                groups.forEachIndexed { idx, group ->
                    val leader = group.flows.firstOrNull { it.id == selectedId } ?: group.flows.first()
                    val ports = group.flows.map { it.destinationPort }.distinct().size
                    val subtitle = if (group.flows.size > 1) {
                        "${group.flows.size} 条 · $ports 端口"
                    } else {
                        "${leader.protocol} :${leader.destinationPort}"
                    }
                    val key = "host:${group.key}"
                    val pos = customPositions[key] ?: Offset(w - hostW - padX, slotY(idx, groups.size))
                    builtNodes.add(
                        GraphNodeBox(
                            key = key,
                            title = group.label,
                            subtitle = subtitle,
                            isApp = false,
                            stackCount = group.flows.size,
                            leader = leader,
                            warning = group.flows.any { it.event != null },
                            rect = Rect(pos, Size(hostW, nodeH))
                        )
                    )
                }

                val builtEdges = ArrayList<Triple<StudioFlow, Offset, Offset>>()
                val nextPulses = ArrayList<PulseEdge>()
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.translate(pan.x, pan.y)
                canvas.scale(zoom, zoom)

                for (group in groups) {
                    val targetNode = builtNodes.firstOrNull { it.key == "host:${group.key}" } ?: continue
                    for ((bucketKey, _, _) in appBuckets) {
                        val members = group.flows.filter { appKey(it.app) == bucketKey }
                        if (members.isEmpty()) continue
                        val sourceNode = builtNodes.firstOrNull { it.key == bucketKey } ?: continue
                        val start = Offset(sourceNode.rect.right, sourceNode.rect.center.y)
                        val end = Offset(targetNode.rect.left, targetNode.rect.center.y)
                        val leader = members.firstOrNull { it.id == selectedId } ?: members.first()
                        builtEdges.add(Triple(leader, start, end))

                        val rx = members.sumOf { it.rx ?: 0.0 }
                        val tx = members.sumOf { it.tx ?: 0.0 }
                        val active = members.any { it.id == selectedId }
                        val warn = members.any { it.event != null }
                        val edgeColor = when {
                            warn -> semantic.warning
                            active -> primaryColor
                            else -> semantic.graphEdge
                        }
                        val strokeW = (1.2f + min(2.2f, (log2(1.0 + rx + tx) / 14.0).toFloat())).dp.toPx()
                        val bend = min(68.dp.toPx(), ((end.x - start.x) / 2f).coerceAtLeast(16.dp.toPx()))
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                        }
                        drawPath(path, color = edgeColor, style = Stroke(width = strokeW, cap = StrokeCap.Round))

                        if (animatePulses && (tx > 0.0 || rx > 0.0)) {
                            nextPulses.add(PulseEdge(start = start, end = end, hasRx = rx > 0.0, hasTx = tx > 0.0))
                        }
                    }
                }

                titlePaint.color = inkColor.toArgb()
                titlePaint.textSize = 12.dp.toPx()
                subPaint.textSize = 10.dp.toPx()
                val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                for (node in builtNodes) {
                    val active = node.leader.id == selectedId ||
                        (node.isApp && drawn.any { it.id == selectedId && appKey(it.app) == node.key })
                    if (node.stackCount > 1) {
                        for (offsetDp in listOf(4.dp.toPx(), 2.dp.toPx())) {
                            drawRoundRect(
                                color = semantic.graphNodeSurface.copy(alpha = 0.72f),
                                topLeft = Offset(node.rect.left + offsetDp, node.rect.top + offsetDp),
                                size = node.rect.size,
                                cornerRadius = corner
                            )
                            drawRoundRect(
                                color = borderColor.copy(alpha = 0.75f),
                                topLeft = Offset(node.rect.left + offsetDp, node.rect.top + offsetDp),
                                size = node.rect.size,
                                cornerRadius = corner,
                                style = Stroke(width = 0.8.dp.toPx())
                            )
                        }
                    }
                    drawRoundRect(
                        color = if (active) semantic.graphNodeSelected else semantic.graphNodeSurface,
                        topLeft = node.rect.topLeft,
                        size = node.rect.size,
                        cornerRadius = corner
                    )
                    drawRoundRect(
                        color = when {
                            node.warning -> semantic.warning
                            active -> primaryColor
                            else -> borderColor
                        },
                        topLeft = node.rect.topLeft,
                        size = node.rect.size,
                        cornerRadius = corner,
                        style = Stroke(width = (if (active) 1.5.dp else 0.8.dp).toPx())
                    )
                    val accentBarColor = if (node.isApp) semantic.upload else semantic.download
                    drawRoundRect(
                        color = accentBarColor,
                        topLeft = Offset(node.rect.left + 6.dp.toPx(), node.rect.top + 11.dp.toPx()),
                        size = Size(2.5.dp.toPx(), node.rect.height - 22.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                    val availW = (node.rect.width - 20.dp.toPx()).coerceAtLeast(16f)
                    canvas.drawText(
                        ellipsizeCanvas(node.title, titlePaint, availW),
                        node.rect.left + 13.dp.toPx(),
                        node.rect.top + 20.dp.toPx(),
                        titlePaint
                    )
                    subPaint.color = (if (node.warning) semantic.warning else mutedColor).toArgb()
                    canvas.drawText(
                        ellipsizeCanvas(node.subtitle, subPaint, availW),
                        node.rect.left + 13.dp.toPx(),
                        node.rect.top + 36.dp.toPx(),
                        subPaint
                    )
                }

                canvas.restore()
                hitState.nodes = builtNodes
                hitState.edges = builtEdges
                if (pulseEdges != nextPulses) {
                    pulseEdges = nextPulses
                }
            }

            if (animatePulses && pulseEdges.isNotEmpty()) {
                PulseOverlayCanvas(
                    edges = pulseEdges,
                    pan = pan,
                    zoom = zoom,
                    uploadColor = semantic.upload,
                    downloadColor = semantic.download
                )
            }
        }
    }
}

@Composable
private fun PulseOverlayCanvas(
    edges: List<PulseEdge>,
    pan: Offset,
    zoom: Float,
    uploadColor: Color,
    downloadColor: Color
) {
    val pulsePhase by rememberInfiniteTransition(label = "PulsePhase").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseProgress"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate(pan.x, pan.y)
        canvas.scale(zoom, zoom)
        val upRadius = 3.dp.toPx()
        val downRadius = 3.4.dp.toPx()
        for (edge in edges) {
            if (edge.hasTx) {
                drawCircle(uploadColor, radius = upRadius, center = cubicPoint(edge.start, edge.end, pulsePhase))
            }
            if (edge.hasRx) {
                drawCircle(downloadColor, radius = downRadius, center = cubicPoint(edge.start, edge.end, 1f - pulsePhase))
            }
        }
        canvas.restore()
    }
}

private fun cubicPoint(start: Offset, end: Offset, t: Float): Offset {
    val bend = min(140f, ((end.x - start.x) / 2f).coerceAtLeast(32f))
    val c1 = Offset(start.x + bend, start.y)
    val c2 = Offset(end.x - bend, end.y)
    val u = 1f - t
    val x = u * u * u * start.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * end.x
    val y = u * u * u * start.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * end.y
    return Offset(x, y)
}

private fun ellipsizeCanvas(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    val ellipsis = "…"
    val count = paint.breakText(text, true, (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f), null)
    return text.take(count) + ellipsis
}

@Composable
private fun SessionFlowCard(
    flow: StudioFlow,
    selected: Boolean,
    sparkline: List<io.wiesler.android.Rate?>,
    onClick: () -> Unit
) {
    val semantic = LocalWieslerColors.current
    val isDark = LocalIsDarkTheme.current
    val routeColor = when (flow.routing) {
        FlowRouting.DIRECT -> semantic.upload
        FlowRouting.PROXY -> semantic.download
        FlowRouting.UNKNOWN -> semantic.warning
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else if (isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.07f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = flow.host,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${flow.app} · ${flow.protocol} :${flow.destinationPort}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(
                    text = flow.exitNode ?: flow.routing.label,
                    containerColor = Color.Transparent,
                    contentColor = routeColor,
                    showDot = true
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "↓ ${formatRate(flow.rx)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = semantic.download
                    )
                    Text(
                        text = "↑ ${formatRate(flow.tx)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = semantic.upload
                    )
                }
                ConnectionSparkline(
                    samples = sparkline,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionInspectorSheet(
    flow: StudioFlow,
    peers: List<StudioFlow>,
    isLive: Boolean,
    onSwitchPeer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf("overview") }
    val nodes = remember(flow) { routePath(flow) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = flow.host,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            SelectionContainer {
                Text(
                    text = "${flow.protocol} · ${flow.destination}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (peers.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    peers.forEach { peer ->
                        FilterChip(
                            selected = peer.id == flow.id,
                            onClick = { onSwitchPeer(peer.id) },
                            label = { Text("${peer.app} :${peer.destinationPort}") }
                        )
                    }
                }
            }

            ExpressiveConnectedChoiceRow(
                options = listOf("overview" to "概览", "events" to "事件", "raw" to "原始"),
                selected = tab,
                onSelect = { tab = it }
            )

            when (tab) {
                "overview" -> {
                    RoutePathStrip(nodes = nodes)
                    SelectionContainer {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            InspectorRow("应用", flow.app)
                            InspectorRow("路由", flow.routing.label)
                            InspectorRow("代理链", flow.route)
                            InspectorRow("规则", flow.rule)
                            InspectorRow("源地址", flow.source)
                            InspectorRow("目标", flow.destination)
                            InspectorRow("速率", "↓ ${formatRate(flow.rx)}  ↑ ${formatRate(flow.tx)}")
                            InspectorRow("累计", "↓ ${formatBytes(flow.download.toDouble())}  ↑ ${formatBytes(flow.upload.toDouble())}")
                            InspectorRow("开始", flow.start.ifBlank { "未知" })
                            InspectorRow("ID", flow.id, showDivider = false)
                        }
                    }
                }
                "events" -> {
                    Text(
                        text = flow.event ?: if (isLive) "未采集会话级事件。" else "无异常事件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                "raw" -> {
                    val pretty = remember(flow) { flow.formattedRawJson() }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        SelectionContainer {
                            Text(
                                text = pretty,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String, showDivider: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (showDivider) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
