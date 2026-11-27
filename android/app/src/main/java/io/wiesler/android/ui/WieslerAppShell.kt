package io.wiesler.android.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wiesler.android.ControllerDiscovery
import io.wiesler.android.DiscoveredController
import io.wiesler.android.R
import io.wiesler.android.StoredSession
import io.wiesler.android.StudioGraphMode
import io.wiesler.android.StudioSourceFilter
import io.wiesler.android.ThemePreference
import io.wiesler.android.eligibleExit
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

data class WieslerActions(
    val onSelectTab: (WieslerTab) -> Unit,
    val onRunQualityCheck: () -> Unit,
    val onToggleQualityMonitor: (Boolean) -> Unit,
    val onSelectQualityPingTarget: (String) -> Unit,
    val onSwitchStudioSource: (Boolean) -> Unit,
    val onToggleFreeze: () -> Unit,
    val onToggleCapturePause: () -> Unit,
    val onReplayIndex: (Int) -> Unit,
    val onBackToNow: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onProtocolChange: (String) -> Unit,
    val onSourceFilterChange: (StudioSourceFilter) -> Unit,
    val onGraphModeChange: (StudioGraphMode) -> Unit,
    val onSelectFlow: (String?) -> Unit,
    val onRefreshEgress: () -> Unit,
    val onToggleGlobal: (Boolean) -> Unit,
    val onToggleForward: (Boolean) -> Unit,
    val onJumpOverseasConnections: () -> Unit,
    val onStartLoss: (String) -> Unit,
    val onStopLoss: () -> Unit,
    val onToggleRfMonitor: (Boolean) -> Unit,
    val onRequestRfPermissions: () -> Unit,
    val onResetRfMonitor: () -> Unit,
    val onRequestUsageAccess: () -> Unit,
    val onRefreshUsage: () -> Unit,
    val onRunActivePrivacy: () -> Unit,
    val onUpdateExpectProxy: (Boolean) -> Unit,
    val onUpdateUploadThresholdKib: (Int) -> Unit,
    val onRunHttpsProbe: () -> Unit,
    val onRefreshSystemNetwork: () -> Unit,
    val onRunEnvironmentCheck: () -> Unit,
    val onGeneratePrivacyJson: () -> Unit,
    val onShareTextReport: () -> Unit,
    val onShareJsonReport: () -> Unit,
    val onPickDatabase: () -> Unit,
    val onSelectHistorySession: (StoredSession) -> Unit,
    val onGenerateHistoryReport: () -> Unit,
    val onShareHistoryReport: () -> Unit,
    val onScanControllers: () -> Unit,
    val onConnectDiscovered: (DiscoveredController) -> Unit,
    val onSaveController: (String, String) -> String?,
    val onDisconnectController: () -> Unit,
    val onSaveExits: (Set<String>) -> Unit,
    val onTogglePulseAnimation: (Boolean) -> Unit,
    val onChangeThemeMode: (ThemePreference) -> Unit,
    val onToggleDynamicColor: (Boolean) -> Unit,
    val onSetControllerDialog: (Boolean) -> Unit,
    val onSetExitsDialog: (Boolean) -> Unit,
    val onSetScopeDialog: (Boolean) -> Unit,
    val onDismissBanner: (Long) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WieslerAppShell(
    state: WieslerUiState,
    actions: WieslerActions
) {
    val semantic = LocalWieslerColors.current
    val isDark = LocalIsDarkTheme.current
    val backdropLayer = rememberGraphicsLayer()
    var backdropTopLeft by remember { mutableStateOf(Offset.Zero) }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomClearance = 90.dp + navBarBottom

    val navItems = remember {
        listOf(
            Triple(WieslerTab.HOME, "首页", Icons.Rounded.Speed),
            Triple(WieslerTab.GRAPH, "图谱", Icons.Rounded.Hub),
            Triple(WieslerTab.MONITOR, "监测", Icons.Rounded.MonitorHeart),
            Triple(WieslerTab.PRIVACY, "隐私", Icons.Rounded.Shield),
            Triple(WieslerTab.HISTORY, "历史", Icons.Rounded.History),
            Triple(WieslerTab.SETTINGS, "设置", Icons.Rounded.Settings)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 将主内容录制到 GraphicsLayer，供悬浮底栏裁剪后实时做硬件毛玻璃模糊。
        // Record main content into a GraphicsLayer so the floating dock can blur its live backdrop.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdropTopLeft = it.positionInRoot() }
                .drawWithContent {
                    backdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdropLayer)
                }
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(Color(0xFF181818))
                                        .border(1.dp, Color(0xFF3E3E3E), RoundedCornerShape(11.dp))
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_logo_mark),
                                        contentDescription = "Wiesler",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Text("Wiesler", style = MaterialTheme.typography.titleLarge)
                            }
                        },
                        actions = {
                            val liveOk = state.controllerEnabled && state.controllerHealthy && !state.capturePaused
                            val connErr = state.controllerEnabled && !state.controllerHealthy && !state.capturePaused
                            StatusPill(
                                text = when {
                                    state.capturePaused -> "已暂停"
                                    liveOk -> "Mihomo 实时"
                                    connErr -> "连接异常"
                                    else -> "仅本机观测"
                                },
                                containerColor = when {
                                    liveOk -> semantic.download.copy(alpha = 0.16f)
                                    connErr -> semantic.danger.copy(alpha = 0.16f)
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = when {
                                    liveOk -> semantic.download
                                    connErr -> semantic.danger
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    )
                }
            ) { innerPadding ->
                CompositionLocalProvider(LocalBottomBarClearance provides bottomClearance) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Crossfade(targetState = state.activeTab, label = "MainTabCrossfade") { tab ->
                            when (tab) {
                                WieslerTab.HOME -> HomeQualityScreen(
                                    state = state,
                                    onRunQualityCheck = actions.onRunQualityCheck,
                                    onToggleContinuousMonitor = actions.onToggleQualityMonitor,
                                    onSelectPingTarget = actions.onSelectQualityPingTarget,
                                    onOpenControllerDialog = { actions.onSetControllerDialog(true) },
                                    onNavigateTab = actions.onSelectTab
                                )
                                WieslerTab.GRAPH -> GraphWorkbenchScreen(
                                    state = state,
                                    onSwitchSource = actions.onSwitchStudioSource,
                                    onToggleFreeze = actions.onToggleFreeze,
                                    onToggleCapturePause = actions.onToggleCapturePause,
                                    onReplayIndex = actions.onReplayIndex,
                                    onBackToNow = actions.onBackToNow,
                                    onQueryChange = actions.onQueryChange,
                                    onProtocolChange = actions.onProtocolChange,
                                    onSourceFilterChange = actions.onSourceFilterChange,
                                    onGraphModeChange = actions.onGraphModeChange,
                                    onSelectFlow = actions.onSelectFlow,
                                    onOpenControllerDialog = { actions.onSetControllerDialog(true) },
                                    onRefreshEgress = actions.onRefreshEgress
                                )
                                WieslerTab.MONITOR -> MonitorScreen(
                                    state = state,
                                    onToggleGlobal = actions.onToggleGlobal,
                                    onToggleForward = actions.onToggleForward,
                                    onOpenControllerDialog = { actions.onSetControllerDialog(true) },
                                    onOpenExitsDialog = { actions.onSetExitsDialog(true) },
                                    onJumpOverseasConnections = actions.onJumpOverseasConnections,
                                    onStartLoss = actions.onStartLoss,
                                    onStopLoss = actions.onStopLoss,
                                    onToggleRfMonitor = actions.onToggleRfMonitor,
                                    onRequestRfPermissions = actions.onRequestRfPermissions,
                                    onResetRfMonitor = actions.onResetRfMonitor,
                                    onRequestUsageAccess = actions.onRequestUsageAccess,
                                    onRefreshUsage = actions.onRefreshUsage,
                                    onRefreshEgress = actions.onRefreshEgress,
                                    onOpenScopeDialog = { actions.onSetScopeDialog(true) }
                                )
                                WieslerTab.PRIVACY -> PrivacyScreen(
                                    state = state,
                                    onRunActivePrivacy = actions.onRunActivePrivacy,
                                    onUpdateExpectProxy = actions.onUpdateExpectProxy,
                                    onUpdateUploadThresholdKib = actions.onUpdateUploadThresholdKib,
                                    onRunHttpsProbe = actions.onRunHttpsProbe,
                                    onRefreshSystemNetwork = actions.onRefreshSystemNetwork,
                                    onRunEnvironmentCheck = actions.onRunEnvironmentCheck,
                                    onGeneratePrivacyJson = actions.onGeneratePrivacyJson,
                                    onShareTextReport = actions.onShareTextReport,
                                    onShareJsonReport = actions.onShareJsonReport
                                )
                                WieslerTab.HISTORY -> HistoryScreen(
                                    state = state,
                                    onPickDatabase = actions.onPickDatabase,
                                    onSelectSession = actions.onSelectHistorySession,
                                    onGenerateReport = actions.onGenerateHistoryReport,
                                    onShareHistoryReport = actions.onShareHistoryReport
                                )
                                WieslerTab.SETTINGS -> SettingsScreen(
                                    state = state,
                                    onScanControllers = actions.onScanControllers,
                                    onConnectDiscovered = actions.onConnectDiscovered,
                                    onOpenManualController = { actions.onSetControllerDialog(true) },
                                    onDisconnectController = actions.onDisconnectController,
                                    onOpenExitsDialog = { actions.onSetExitsDialog(true) },
                                    onTogglePulseAnimation = actions.onTogglePulseAnimation,
                                    onToggleExpectProxy = actions.onUpdateExpectProxy,
                                    onChangeThemeMode = actions.onChangeThemeMode,
                                    onToggleDynamicColor = actions.onToggleDynamicColor,
                                    onOpenScopeDialog = { actions.onSetScopeDialog(true) }
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingGlassNavBar(
            items = navItems,
            activeTab = state.activeTab,
            isDark = isDark,
            backdropLayer = backdropLayer,
            backdropTopLeft = backdropTopLeft,
            onSelectTab = actions.onSelectTab,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        )

        TopAlertBannerHost(
            banner = state.topBanner,
            onDismiss = actions.onDismissBanner,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }

    if (state.showControllerDialog) {
        ControllerConfigDialog(
            initialAddress = state.controllerAddress,
            hasExistingSecret = state.hasSecret,
            onSave = { addr, secret ->
                val err = actions.onSaveController(addr, secret)
                if (err == null) actions.onSetControllerDialog(false)
                err
            },
            onDisconnect = {
                actions.onDisconnectController()
                actions.onSetControllerDialog(false)
            },
            onDismiss = { actions.onSetControllerDialog(false) }
        )
    }

    if (state.showExitsDialog) {
        OverseasExitsDialog(
            candidates = state.availableExits,
            initialSelected = state.overseasExits,
            onSave = { selected ->
                actions.onSaveExits(selected)
                actions.onSetExitsDialog(false)
            },
            onDismiss = { actions.onSetExitsDialog(false) }
        )
    }

    if (state.showScopeDialog) {
        AlertDialog(
            onDismissRequest = { actions.onSetScopeDialog(false) },
            title = { Text("数据来源与统计边界") },
            text = {
                Text(
                    "全局流量来自 Android TrafficStats，只在前台采样；VPN 下系统接口可能重复计量，不等同套餐账单。\n\n" +
                        "海外转发仅统计你指定的实际出口节点（chains[0]），按连接 ID 与开始时间差分，不自动推断地理位置。\n\n" +
                        "双路公网出口分别按系统路由与 Mihomo HTTP/Mixed 端口请求 ipify 与 ipwho.is，严格校验返回 IP 一致性，不向外部服务发送控制器密钥。\n\n" +
                        "Android 客户端不启用 VpnService，不修改手机现有代理，也不支持非 root 底层明文 DNS 抓包。"
                )
            },
            confirmButton = {
                TextButton(onClick = { actions.onSetScopeDialog(false) }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun FloatingGlassNavBar(
    items: List<Triple<WieslerTab, String, ImageVector>>,
    activeTab: WieslerTab,
    isDark: Boolean,
    backdropLayer: GraphicsLayer,
    backdropTopLeft: Offset,
    onSelectTab: (WieslerTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(32.dp)
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { 22.dp.toPx() }
    val supportsBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var dockTopLeft by remember { mutableStateOf(Offset.Zero) }

    val glassTint = MaterialTheme.colorScheme.surfaceContainerLow.copy(
        alpha = when {
            supportsBackdropBlur && isDark -> 0.62f
            supportsBackdropBlur && !isDark -> 0.72f
            isDark -> 0.90f
            else -> 0.94f
        }
    )
    val sheenBrush = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.02f), Color.Transparent)
            } else {
                listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.16f), Color.Transparent)
            }
        )
    }
    val rimColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.09f)
    val shadowColor = if (isDark) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.14f)

    val haptics = LocalHapticFeedback.current
    val currentActiveTab by rememberUpdatedState(activeTab)
    val currentOnSelectTab by rememberUpdatedState(onSelectTab)
    val slotCount = items.size.coerceAtLeast(1)
    val horizontalPadDp = 8.dp
    val horizontalPadPx = with(density) { horizontalPadDp.toPx() }
    val pillHeightDp = 44.dp
    val minPillWidthPx = with(density) { 42.dp.toPx() }
    val maxPillWidthPx = with(density) { 52.dp.toPx() }

    var dockWidthPx by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableFloatStateOf(activeTab.index.toFloat()) }

    val settledIndex by animateFloatAsState(
        targetValue = activeTab.index.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.76f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "DockPillPosition"
    )
    val displayIndex = if (isScrubbing) scrubIndex else settledIndex
    val pillScaleX by animateFloatAsState(
        targetValue = if (isScrubbing) 1.10f else 1.0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
        label = "DockPillScaleX"
    )
    val pillScaleY by animateFloatAsState(
        targetValue = if (isScrubbing) 1.06f else 1.0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
        label = "DockPillScaleY"
    )

    val pillFill = if (isScrubbing) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.28f else 0.22f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.20f else 0.14f)
    }
    val pillBorder = if (isScrubbing) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.45f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.26f else 0.20f)
    }

    Box(
        modifier = modifier
            .widthIn(max = 368.dp)
            .fillMaxWidth()
            .shadow(elevation = 16.dp, shape = pillShape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(pillShape)
            .height(60.dp)
            .onGloballyPositioned { dockTopLeft = it.positionInRoot() }
            .onSizeChanged { dockWidthPx = it.width }
            // 统一处理点击与底栏内横向滑动聚焦：手指在图标间拖动时胶囊实时跟手并切换页面。
            // Unified tap + horizontal scrub gesture so the focus pill tracks finger motion across icons.
            .pointerInput(slotCount, horizontalPadPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val innerW = (size.width - horizontalPadPx * 2f).coerceAtLeast(1f)
                    val slotW = innerW / slotCount
                    fun positionToSlot(x: Float): Float =
                        ((x - horizontalPadPx) / slotW - 0.5f).coerceIn(0f, (slotCount - 1).toFloat())

                    val startX = down.position.x
                    val downSlot = positionToSlot(startX)
                    var lastIdx = currentActiveTab.index
                    var moved = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!moved) {
                                val tapIdx = downSlot.roundToInt().coerceIn(0, slotCount - 1)
                                if (tapIdx != currentActiveTab.index) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnSelectTab(items[tapIdx].first)
                                }
                            }
                            isScrubbing = false
                            break
                        }
                        val posX = change.position.x
                        if (!moved && abs(posX - startX) > viewConfiguration.touchSlop * 0.45f) {
                            moved = true
                            isScrubbing = true
                        }
                        if (moved) {
                            change.consume()
                            val slotPos = positionToSlot(posX)
                            scrubIndex = slotPos
                            val nearest = slotPos.roundToInt().coerceIn(0, slotCount - 1)
                            if (nearest != lastIdx) {
                                lastIdx = nearest
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnSelectTab(items[nearest].first)
                            }
                        }
                    }
                    isScrubbing = false
                }
            }
    ) {
        // Android 12+ 实时裁剪并模糊底栏背后内容层，低版本平滑退化为高透明磨砂底色。
        // Clip and blur the live backdrop layer behind the dock on API 31+; translucent fallback on API 30.
        if (supportsBackdropBlur) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        shape = pillShape
                        renderEffect = BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
                    }
                    .drawBehind {
                        val dx = backdropTopLeft.x - dockTopLeft.x
                        val dy = backdropTopLeft.y - dockTopLeft.y
                        translate(left = dx, top = dy) {
                            drawLayer(backdropLayer)
                        }
                    }
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(glassTint)
                .background(sheenBrush)
                .border(1.dp, rimColor, pillShape)
        )

        // 6 项无字底栏中心极淡发丝分组线（监测 3 项 | 工具 3 项），保持 6 图标等距同时建立视觉秩序。
        // Subtle 1dp center hairline grouping the 6 icon-only slots into 3+3 without shifting slot math.
        if (slotCount == 6) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .height(18.dp)
                    .background(rimColor)
            )
        }

        // 单一共享滑动聚焦胶囊，随槽宽等比自适应居中，点击弹性滑行或横向拖动实时跟手。
        // Single shared sliding focus pill sized proportionally to slot width for balanced 6-icon alignment.
        if (dockWidthPx > 0) {
            val innerWidthPx = (dockWidthPx - horizontalPadPx * 2f).coerceAtLeast(1f)
            val slotWidthPx = innerWidthPx / slotCount
            val pillWidthPx = (slotWidthPx * 0.84f).coerceIn(minPillWidthPx, maxPillWidthPx)
            val pillWidthDp = with(density) { pillWidthPx.toDp() }
            val pillOffsetX = (horizontalPadPx + (displayIndex + 0.5f) * slotWidthPx - pillWidthPx / 2f).roundToInt()
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(pillOffsetX, 0) }
                    .size(width = pillWidthDp, height = pillHeightDp)
                    .graphicsLayer {
                        scaleX = pillScaleX
                        scaleY = pillScaleY
                    }
                    .clip(CircleShape)
                    .background(pillFill)
                    .border(1.dp, pillBorder, CircleShape)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadDp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { idx, (tab, title, icon) ->
                val focus = (1f - abs(displayIndex - idx)).coerceIn(0f, 1f)
                val iconTint = lerp(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.primary,
                    focus
                )
                val iconScale = 1f + 0.12f * focus
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(pillHeightDp)
                        .semantics {
                            contentDescription = title
                            role = Role.Tab
                            selected = tab == activeTab
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopAlertBannerHost(
    banner: TopBannerMessage?,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // 保留最后一条横幅内容，确保收起动画期间不瞬间变空闪烁。
    // Keep the last banner payload during exit animation to avoid blank flashes.
    var lastBanner by remember { mutableStateOf(banner) }
    if (banner != null) lastBanner = banner

    LaunchedEffect(banner?.id) {
        if (banner != null) {
            delay(banner.durationMs)
            onDismiss(banner.id)
        }
    }

    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically(initialOffsetY = { -it - 24 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it - 24 }) + fadeOut(),
        modifier = modifier
    ) {
        val active = lastBanner ?: return@AnimatedVisibility
        val semantic = LocalWieslerColors.current
        val isDark = LocalIsDarkTheme.current
        val (accent, icon) = when (active.tone) {
            BannerTone.ERROR -> semantic.danger to Icons.Rounded.ErrorOutline
            BannerTone.WARNING -> semantic.warning to Icons.Rounded.WarningAmber
            BannerTone.SUCCESS -> semantic.success to Icons.Rounded.CheckCircle
            BannerTone.INFO -> MaterialTheme.colorScheme.primary to Icons.Rounded.Info
        }
        val surfaceColor = if (isDark) Color(0xFF1F1F22) else Color(0xFFFDFDFE)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = surfaceColor,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, accent.copy(alpha = if (isDark) 0.55f else 0.42f)),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clickable { onDismiss(active.id) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = active.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!active.detail.isNullOrBlank()) {
                        Text(
                            text = active.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { onDismiss(active.id) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭提示",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControllerConfigDialog(
    initialAddress: String,
    hasExistingSecret: Boolean,
    onSave: (String, String) -> String?,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val semantic = LocalWieslerColors.current
    var address by remember(initialAddress) { mutableStateOf(initialAddress) }
    var secret by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接手机控制器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = semantic.danger.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, semantic.danger.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = semantic.danger,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.labelMedium,
                                color = semantic.danger
                            )
                        }
                    }
                }
                Text(
                    "填写 Clash / Mihomo / sing-box 的 External Controller 地址。本机回环地址可直接填 127.0.0.1:端口（自动补全 http://），远程地址必须使用 HTTPS。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; errorMessage = null },
                    label = { Text("控制器根地址") },
                    placeholder = { Text("127.0.0.1:9090 或 127.0.0.1:12440") },
                    isError = errorMessage != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; errorMessage = null },
                    label = { Text(if (hasExistingSecret) "Secret（留空保留已保存或填新值）" else "Secret（可选，Keystore 加密保存）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = ControllerDiscovery.normalizeControllerAddress(address)
                    val value = normalized.getOrElse { err ->
                        errorMessage = err.message ?: "控制器地址格式无效"
                        return@TextButton
                    }
                    address = value
                    errorMessage = onSave(value, secret)
                }
            ) { Text("连接") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDisconnect) { Text("断开") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun OverseasExitsDialog(
    candidates: List<String>,
    initialSelected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var manualMode by remember { mutableStateOf(candidates.isEmpty()) }
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    var manualText by remember(initialSelected) { mutableStateOf(initialSelected.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("海外出口节点（chains[0]）") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (manualMode) {
                    Text("每行填写一个实际出口节点名称（非代理组名）：", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        minLines = 3,
                        placeholder = { Text("例如：\nJP-01\nSG-02") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("从当前活跃连接勾选要计入海外转发的实际出口：", style = MaterialTheme.typography.bodyMedium)
                    candidates.forEach { exit ->
                        val checked = exit in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - exit else selected + exit
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) selected + exit else selected - exit
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(exit, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = if (manualMode) {
                        manualText.lines().map { it.trim() }.filter(::eligibleExit).toSet()
                    } else {
                        selected
                    }
                    onSave(result)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (candidates.isNotEmpty()) {
                    TextButton(onClick = { manualMode = !manualMode }) {
                        Text(if (manualMode) "从列表选择" else "手动填写")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun WieslerPreview() {
    WieslerExpressiveTheme(themePreference = ThemePreference.DARK, dynamicColor = false) {
        WieslerAppShell(
            state = WieslerUiState(),
            actions = WieslerActions(
                onSelectTab = {}, onRunQualityCheck = {}, onToggleQualityMonitor = {},
                onSelectQualityPingTarget = {}, onSwitchStudioSource = {}, onToggleFreeze = {},
                onToggleCapturePause = {}, onReplayIndex = {}, onBackToNow = {},
                onQueryChange = {}, onProtocolChange = {}, onSourceFilterChange = {}, onGraphModeChange = {},
                onSelectFlow = {}, onRefreshEgress = {}, onToggleGlobal = {},
                onToggleForward = {}, onJumpOverseasConnections = {}, onStartLoss = {},
                onStopLoss = {}, onToggleRfMonitor = {}, onRequestRfPermissions = {},
                onResetRfMonitor = {}, onRequestUsageAccess = {}, onRefreshUsage = {},
                onRunActivePrivacy = {}, onUpdateExpectProxy = {}, onUpdateUploadThresholdKib = {},
                onRunHttpsProbe = {}, onRefreshSystemNetwork = {}, onRunEnvironmentCheck = {},
                onGeneratePrivacyJson = {}, onShareTextReport = {}, onShareJsonReport = {},
                onPickDatabase = {}, onSelectHistorySession = {}, onGenerateHistoryReport = {},
                onShareHistoryReport = {}, onScanControllers = {}, onConnectDiscovered = {},
                onSaveController = { _, _ -> null }, onDisconnectController = {},
                onSaveExits = {}, onTogglePulseAnimation = {}, onChangeThemeMode = {},
                onToggleDynamicColor = {}, onSetControllerDialog = {}, onSetExitsDialog = {},
                onSetScopeDialog = {}, onDismissBanner = {}
            )
        )
    }
}
