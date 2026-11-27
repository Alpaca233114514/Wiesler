package io.wiesler.android.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wiesler.android.DiscoveredController
import io.wiesler.android.MonitorPerformanceMode
import io.wiesler.android.R
import io.wiesler.android.ThemePreference

private enum class SettingsSection(val title: String) {
    CONTROLLER("控制器"),
    MONITORING("监测与核查"),
    APPEARANCE("外观"),
    ABOUT("关于")
}

@Composable
fun SettingsScreen(
    state: WieslerUiState,
    onScanControllers: () -> Unit,
    onConnectDiscovered: (DiscoveredController) -> Unit,
    onOpenManualController: () -> Unit,
    onDisconnectController: () -> Unit,
    onOpenExitsDialog: () -> Unit,
    onTogglePulseAnimation: (Boolean) -> Unit,
    onChangePerformanceMode: (MonitorPerformanceMode) -> Unit,
    onToggleExpectProxy: (Boolean) -> Unit,
    onChangeThemeMode: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onOpenScopeDialog: () -> Unit
) {
    var activeSection by remember { mutableStateOf<SettingsSection?>(null) }

    BackHandler(enabled = activeSection != null) {
        activeSection = null
    }

    // 设置页二级导航过渡：进入子分类左滑，返回主列表右滑。
    // Two-level settings navigation: forward slide into category subpage, reverse slide back to root.
    AnimatedContent(
        targetState = activeSection,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it / 6 } + fadeOut(tween(140)))
            } else {
                (slideInHorizontally(tween(220)) { -it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(140)))
            }
        },
        label = "SettingsNavigation"
    ) { section ->
        when (section) {
            null -> SettingsRootMenu(
                state = state,
                onSelectSection = { activeSection = it }
            )
            SettingsSection.CONTROLLER -> ControllerSettingsPage(
                state = state,
                onBack = { activeSection = null },
                onScanControllers = onScanControllers,
                onConnectDiscovered = onConnectDiscovered,
                onOpenManualController = onOpenManualController,
                onDisconnectController = onDisconnectController
            )
            SettingsSection.MONITORING -> MonitoringSettingsPage(
                state = state,
                onBack = { activeSection = null },
                onTogglePulseAnimation = onTogglePulseAnimation,
                onChangePerformanceMode = onChangePerformanceMode,
                onToggleExpectProxy = onToggleExpectProxy,
                onOpenExitsDialog = onOpenExitsDialog
            )
            SettingsSection.APPEARANCE -> AppearanceSettingsPage(
                state = state,
                onBack = { activeSection = null },
                onChangeThemeMode = onChangeThemeMode,
                onToggleDynamicColor = onToggleDynamicColor
            )
            SettingsSection.ABOUT -> AboutSettingsPage(
                onBack = { activeSection = null },
                onOpenScopeDialog = onOpenScopeDialog
            )
        }
    }
}

@Composable
private fun SettingsRootMenu(
    state: WieslerUiState,
    onSelectSection: (SettingsSection) -> Unit
) {
    val semantic = LocalWieslerColors.current
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current

    val controllerSummary = when {
        state.controllerEnabled && state.controllerHealthy -> "已连接"
        state.controllerEnabled -> "连接异常"
        else -> "未连接"
    }
    val controllerSummaryColor = when {
        state.controllerEnabled && state.controllerHealthy -> semantic.success
        state.controllerEnabled -> semantic.danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

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
        item(key = "settings_header") {
            ExpressivePageHeader(title = "设置")
        }

        item(key = "settings_primary_group") {
            ExpressiveSectionCard(
                contentPadding = PaddingValues(vertical = 4.dp),
                contentSpacing = 0
            ) {
                SettingsCategoryRow(
                    icon = Icons.Rounded.Router,
                    title = SettingsSection.CONTROLLER.title,
                    value = controllerSummary,
                    valueColor = controllerSummaryColor,
                    onClick = { onSelectSection(SettingsSection.CONTROLLER) }
                )
                SettingsHairline()
                SettingsCategoryRow(
                    icon = Icons.Rounded.Tune,
                    title = SettingsSection.MONITORING.title,
                    value = state.preferences.performanceMode.label,
                    onClick = { onSelectSection(SettingsSection.MONITORING) }
                )
                SettingsHairline()
                SettingsCategoryRow(
                    icon = Icons.Rounded.Palette,
                    title = SettingsSection.APPEARANCE.title,
                    value = state.preferences.themeMode.label,
                    onClick = { onSelectSection(SettingsSection.APPEARANCE) }
                )
            }
        }

        item(key = "settings_about_group") {
            ExpressiveSectionCard(
                contentPadding = PaddingValues(vertical = 4.dp),
                contentSpacing = 0
            ) {
                SettingsCategoryRow(
                    icon = Icons.Rounded.Info,
                    title = SettingsSection.ABOUT.title,
                    value = "0.1.1",
                    onClick = { onSelectSection(SettingsSection.ABOUT) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ControllerSettingsPage(
    state: WieslerUiState,
    onBack: () -> Unit,
    onScanControllers: () -> Unit,
    onConnectDiscovered: (DiscoveredController) -> Unit,
    onOpenManualController: () -> Unit,
    onDisconnectController: () -> Unit
) {
    val semantic = LocalWieslerColors.current
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
        item(key = "back_header") {
            SettingsSubPageHeader(
                title = SettingsSection.CONTROLLER.title,
                onBack = onBack,
                trailing = if (state.discoveringControllers) {
                    { LoadingIndicator(modifier = Modifier.size(28.dp)) }
                } else null
            )
        }

        item(key = "controller_status") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.controllerEnabled) state.controllerAddress else "未启用控制器",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = if (state.controllerEnabled) FontFamily.Monospace else FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.hasSecret) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(
                            text = "已加密",
                            containerColor = semantic.success.copy(alpha = 0.14f),
                            contentColor = semantic.success
                        )
                    }
                }

                Text(
                    text = state.connectionState,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.controllerEnabled && !state.controllerHealthy) semantic.danger
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onScanControllers,
                        enabled = !state.discoveringControllers,
                        colors = wieslerPrimaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.discoveringControllers) "扫描中…" else "扫描端口",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenManualController,
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Text("手动配置", maxLines = 1, softWrap = false)
                    }
                }

                if (state.controllerEnabled) {
                    OutlinedButton(
                        onClick = onDisconnectController,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("断开并清除密钥")
                    }
                }

                if (state.discoveryFailed || state.discoveringControllers) {
                    Text(
                        text = state.discoveryStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.discoveryFailed) semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.discoveredControllers.isNotEmpty()) {
            item(key = "discovered_list") {
                ExpressiveSectionCard(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    contentSpacing = 0
                ) {
                    state.discoveredControllers.forEachIndexed { idx, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = item.address,
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when {
                                        item.ready -> "Mihomo ${item.version.orEmpty()}".trim()
                                        item.requiresAuth -> "需要 Secret"
                                        else -> "已发现"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (item.ready) semantic.success else semantic.warning
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            FilledTonalButton(
                                onClick = { onConnectDiscovered(item) },
                                colors = wieslerSecondaryButtonColors(),
                                shape = MaterialTheme.shapes.small,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(if (item.requiresAuth) "密钥" else "连接")
                            }
                        }
                        if (idx < state.discoveredControllers.lastIndex) {
                            SettingsHairline()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringSettingsPage(
    state: WieslerUiState,
    onBack: () -> Unit,
    onTogglePulseAnimation: (Boolean) -> Unit,
    onChangePerformanceMode: (MonitorPerformanceMode) -> Unit,
    onToggleExpectProxy: (Boolean) -> Unit,
    onOpenExitsDialog: () -> Unit
) {
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
        item(key = "back_header") {
            SettingsSubPageHeader(title = SettingsSection.MONITORING.title, onBack = onBack)
        }

        item(key = "performance_mode_group") {
            ExpressiveSectionCard {
                Text("实时检测功耗与性能", style = MaterialTheme.typography.titleMedium)
                ExpressiveConnectedChoiceRow(
                    options = MonitorPerformanceMode.entries.map { it to it.label },
                    selected = state.preferences.performanceMode,
                    onSelect = onChangePerformanceMode
                )
                Text(
                    text = state.preferences.performanceMode.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item(key = "monitoring_group") {
            ExpressiveSectionCard(
                contentPadding = PaddingValues(vertical = 4.dp),
                contentSpacing = 0
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图谱脉冲动效", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.preferences.pulseAnimation,
                        onCheckedChange = onTogglePulseAnimation
                    )
                }
                SettingsHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全代理隐私核查", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.preferences.expectProxy,
                        onCheckedChange = onToggleExpectProxy
                    )
                }
                SettingsHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenExitsDialog)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("海外转发出口", style = MaterialTheme.typography.titleMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (state.overseasExits.isEmpty()) "未指定" else "${state.overseasExits.size} 个",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPage(
    state: WieslerUiState,
    onBack: () -> Unit,
    onChangeThemeMode: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit
) {
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
        item(key = "back_header") {
            SettingsSubPageHeader(title = SettingsSection.APPEARANCE.title, onBack = onBack)
        }

        item(key = "appearance_group") {
            ExpressiveSectionCard {
                Text("配色", style = MaterialTheme.typography.titleMedium)
                ExpressiveConnectedChoiceRow(
                    options = ThemePreference.entries.map { it to it.label },
                    selected = state.preferences.themeMode,
                    onSelect = onChangeThemeMode
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("动态取色", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = state.preferences.dynamicColor,
                            onCheckedChange = onToggleDynamicColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSettingsPage(
    onBack: () -> Unit,
    onOpenScopeDialog: () -> Unit
) {
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current
    val isDark = LocalIsDarkTheme.current

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
        item(key = "back_header") {
            SettingsSubPageHeader(title = SettingsSection.ABOUT.title, onBack = onBack)
        }

        // 原顶栏大图标与品牌字标迁至关于页集中展示。
        // Showcase the app logo mark and Wiesler brand moved here from the removed top bar.
        item(key = "brand_showcase") {
            ExpressiveSectionCard(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
                contentSpacing = 0
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF161618))
                            .border(
                                width = 1.dp,
                                color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo_mark),
                            contentDescription = "Wiesler",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Wiesler",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.3).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "v0.1.1",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item(key = "about_info") {
            ExpressiveSectionCard(
                contentPadding = PaddingValues(vertical = 4.dp),
                contentSpacing = 0
            ) {
                AboutInfoRow(label = "模式", value = "只读网络观测")
                SettingsHairline()
                AboutInfoRow(label = "协议", value = "Apache-2.0")
                SettingsHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenScopeDialog)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("数据来源与统计边界", style = MaterialTheme.typography.titleMedium)
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSubPageHeader(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回设置",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsHairline() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
