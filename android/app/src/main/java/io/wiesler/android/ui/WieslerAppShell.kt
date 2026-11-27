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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.wiesler.android.ControllerDiscovery
import io.wiesler.android.DiscoveredController
import io.wiesler.android.MonitorPerformanceMode
import io.wiesler.android.StoredSession
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
    val onChangePerformanceMode: (MonitorPerformanceMode) -> Unit,
    val onChangeThemeMode: (ThemePreference) -> Unit,
    val onToggleDynamicColor: (Boolean) -> Unit,
    val onSetControllerDialog: (Boolean) -> Unit,
    val onSetExitsDialog: (Boolean) -> Unit,
    val onSetScopeDialog: (Boolean) -> Unit,
    val onDismissBanner: (Long) -> Unit
)

private class OffsetHolder(var value: Offset = Offset.Zero)

@Composable
fun WieslerAppShell(
    state: WieslerUiState,
    actions: WieslerActions
) {
    val isDark = LocalIsDarkTheme.current
    val enableBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        state.preferences.performanceMode.allowBackdropBlur
    // 仅在真正启用毛玻璃时分配离屏 GraphicsLayer，避免默认模式下占用数十 MB 全屏 EGL 显存。
    // Allocate offscreen GraphicsLayer only when backdrop blur is active to avoid multi-10MB EGL FBO VRAM.
    val backdropLayer: GraphicsLayer? = if (enableBackdropBlur) rememberGraphicsLayer() else null
    val backdropTopLeft = remember { OffsetHolder() }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 仅在启用毛玻璃模糊时录制离屏 GraphicsLayer；极致省电模式直接渲染主树，消除整屏离屏重绘开销。
        // Record offscreen GraphicsLayer only when backdrop blur is enabled; ECO mode renders directly with zero blur pass.
        val contentModifier = if (enableBackdropBlur && backdropLayer != null) {
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdropTopLeft.value = it.positionInRoot() }
                .drawWithContent {
                    backdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(backdropLayer)
                }
        } else {
            Modifier.fillMaxSize()
        }
        Box(modifier = contentModifier) {
            CompositionLocalProvider(
                LocalTopBarClearance provides statusBarTop,
                LocalBottomBarClearance provides bottomClearance
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
                            onChangePerformanceMode = actions.onChangePerformanceMode,
                            onToggleExpectProxy = actions.onUpdateExpectProxy,
                            onChangeThemeMode = actions.onChangeThemeMode,
                            onToggleDynamicColor = actions.onToggleDynamicColor,
                            onOpenScopeDialog = { actions.onSetScopeDialog(true) }
                        )
                    }
                }
            }
        }

        FloatingGlassNavBar(
            items = navItems,
            activeTab = state.activeTab,
            isDark = isDark,
            enableBackdropBlur = enableBackdropBlur,
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
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
                    "全局流量来自 Android TrafficStats，仅在前台采样；VPN 下可能重复计量，不等同套餐账单。\n\n" +
                        "海外转发仅统计指定的实际出口节点（chains[0]），按连接差分，不推断地理位置。\n\n" +
                        "双路公网出口分别按系统路由与 Mihomo 端口查询 ipify 与 ipwho.is，不发送控制器密钥。\n\n" +
                        "本应用不启用 VpnService，不修改手机代理，也不抓取非 root 底层明文 DNS。"
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
    enableBackdropBlur: Boolean,
    backdropLayer: GraphicsLayer?,
    backdropTopLeft: OffsetHolder,
    onSelectTab: (WieslerTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(30.dp)
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { 16.dp.toPx() }
    val cachedBlurEffect = remember(blurRadiusPx, enableBackdropBlur) {
        if (enableBackdropBlur) BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp) else null
    }
    val dockTopLeft = remember { OffsetHolder() }

    val glassTint = MaterialTheme.colorScheme.surfaceContainerLow.copy(
        alpha = when {
            enableBackdropBlur && isDark -> 0.66f
            enableBackdropBlur && !isDark -> 0.76f
            isDark -> 0.92f
            else -> 0.95f
        }
    )
    val sheenBrush = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.015f), Color.Transparent)
            } else {
                listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.14f), Color.Transparent)
            }
        )
    }
    val rimColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val shadowColor = if (isDark) Color.Black.copy(alpha = 0.58f) else Color.Black.copy(alpha = 0.12f)

    val haptics = LocalHapticFeedback.current
    val currentActiveTab by rememberUpdatedState(activeTab)
    val currentOnSelectTab by rememberUpdatedState(onSelectTab)
    val slotCount = items.size.coerceAtLeast(1)
    val horizontalPadDp = 8.dp
    val horizontalPadPx = with(density) { horizontalPadDp.toPx() }
    val pillHeightDp = 42.dp
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
        targetValue = if (isScrubbing) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
        label = "DockPillScaleX"
    )
    val pillScaleY by animateFloatAsState(
        targetValue = if (isScrubbing) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium),
        label = "DockPillScaleY"
    )

    val pillFill = if (isScrubbing) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.26f else 0.20f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.18f else 0.13f)
    }
    val pillBorder = if (isScrubbing) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.50f else 0.40f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.24f else 0.18f)
    }

    Box(
        modifier = modifier
            .widthIn(max = 368.dp)
            .fillMaxWidth()
            .shadow(elevation = 14.dp, shape = pillShape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(pillShape)
            .height(58.dp)
            .onGloballyPositioned { dockTopLeft.value = it.positionInRoot() }
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
        if (enableBackdropBlur && cachedBlurEffect != null && backdropLayer != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        shape = pillShape
                        renderEffect = cachedBlurEffect
                    }
                    .drawBehind {
                        val dx = backdropTopLeft.value.x - dockTopLeft.value.x
                        val dy = backdropTopLeft.value.y - dockTopLeft.value.y
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
                .border(0.75.dp, rimColor, pillShape)
        )

        if (slotCount == 6) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(0.75.dp)
                    .height(16.dp)
                    .background(rimColor)
            )
        }

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
                    .border(0.75.dp, pillBorder, CircleShape)
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
                val iconScale = 1f + 0.10f * focus
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
                            .size(21.dp)
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
        val surfaceColor = if (isDark) Color(0xFF1C1C1F) else Color(0xFFFFFFFF)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surfaceColor,
            shadowElevation = 10.dp,
            border = BorderStroke(0.75.dp, accent.copy(alpha = if (isDark) 0.50f else 0.38f)),
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
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(17.dp)
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
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
        title = { Text("配置控制器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = semantic.danger.copy(alpha = 0.14f),
                        border = BorderStroke(0.75.dp, semantic.danger.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
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
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; errorMessage = null },
                    label = { Text("控制器地址") },
                    placeholder = { Text("127.0.0.1:9090") },
                    isError = errorMessage != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; errorMessage = null },
                    label = { Text(if (hasExistingSecret) "Secret（留空保持不变）" else "Secret（可选）") },
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
        title = { Text("海外出口节点") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (manualMode) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        minLines = 3,
                        placeholder = { Text("每行一个节点名，例如 JP-01") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
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
                onQueryChange = {}, onProtocolChange = {}, onSourceFilterChange = {},
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
                onSaveExits = {}, onTogglePulseAnimation = {}, onChangePerformanceMode = {}, onChangeThemeMode = {},
                onToggleDynamicColor = {}, onSetControllerDialog = {}, onSetExitsDialog = {},
                onSetScopeDialog = {}, onDismissBanner = {}
            )
        )
    }
}
