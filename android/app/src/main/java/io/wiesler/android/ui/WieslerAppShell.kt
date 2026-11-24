package io.wiesler.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.wiesler.android.DiscoveredController
import io.wiesler.android.R
import io.wiesler.android.StoredSession
import io.wiesler.android.StudioSourceFilter
import io.wiesler.android.ThemePreference
import io.wiesler.android.eligibleExit
import java.net.URI

data class WieslerActions(
    val onSelectTab: (WieslerTab) -> Unit,
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
    val onSetScopeDialog: (Boolean) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WieslerAppShell(
    state: WieslerUiState,
    actions: WieslerActions
) {
    val semantic = LocalWieslerColors.current
    Scaffold(
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
                    val liveOk = state.controllerEnabled && !state.capturePaused
                    StatusPill(
                        text = when {
                            state.capturePaused -> "已暂停"
                            state.controllerEnabled -> "Mihomo 实时"
                            else -> "仅本机观测"
                        },
                        containerColor = if (liveOk) semantic.download.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (liveOk) semantic.download
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                val items = listOf(
                    Triple(WieslerTab.GRAPH, "图谱", Icons.Rounded.Hub),
                    Triple(WieslerTab.MONITOR, "监测", Icons.Rounded.MonitorHeart),
                    Triple(WieslerTab.PRIVACY, "隐私", Icons.Rounded.Shield),
                    Triple(WieslerTab.HISTORY, "历史", Icons.Rounded.History),
                    Triple(WieslerTab.SETTINGS, "设置", Icons.Rounded.Settings)
                )
                items.forEach { (tab, title, icon) ->
                    NavigationBarItem(
                        selected = state.activeTab == tab,
                        onClick = { actions.onSelectTab(tab) },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = state.activeTab, label = "MainTabCrossfade") { tab ->
                when (tab) {
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
private fun ControllerConfigDialog(
    initialAddress: String,
    hasExistingSecret: Boolean,
    onSave: (String, String) -> String?,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember(initialAddress) { mutableStateOf(initialAddress) }
    var secret by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接手机 Mihomo 控制器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "填写 Clash Meta / Mihomo 的 External Controller 地址。明文 HTTP 仅允许本机回环地址，远程地址必须使用 HTTPS。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; errorMessage = null },
                    label = { Text("控制器根地址") },
                    placeholder = { Text("http://127.0.0.1:9090") },
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
                if (errorMessage != null) {
                    Text(errorMessage!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = address.trim().trimEnd('/')
                    val uri = runCatching { URI(value) }.getOrNull()
                    val loopback = uri?.host in listOf("127.0.0.1", "localhost", "[::1]")
                    if (uri == null || uri.host == null || uri.userInfo != null || uri.query != null ||
                        uri.fragment != null || !uri.path.isNullOrEmpty() ||
                        (uri.scheme != "https" && !(uri.scheme == "http" && loopback))
                    ) {
                        errorMessage = "请使用本机 HTTP 或 HTTPS 根地址，不包含路径与密钥"
                        return@TextButton
                    }
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
                onSelectTab = {}, onSwitchStudioSource = {}, onToggleFreeze = {},
                onToggleCapturePause = {}, onReplayIndex = {}, onBackToNow = {},
                onQueryChange = {}, onProtocolChange = {}, onSourceFilterChange = {},
                onSelectFlow = {}, onRefreshEgress = {}, onToggleGlobal = {},
                onToggleForward = {}, onJumpOverseasConnections = {}, onStartLoss = {},
                onStopLoss = {}, onRequestUsageAccess = {}, onRefreshUsage = {},
                onRunActivePrivacy = {}, onUpdateExpectProxy = {}, onUpdateUploadThresholdKib = {},
                onRunHttpsProbe = {}, onRefreshSystemNetwork = {}, onRunEnvironmentCheck = {},
                onGeneratePrivacyJson = {}, onShareTextReport = {}, onShareJsonReport = {},
                onPickDatabase = {}, onSelectHistorySession = {}, onGenerateHistoryReport = {},
                onShareHistoryReport = {}, onScanControllers = {}, onConnectDiscovered = {},
                onSaveController = { _, _ -> null }, onDisconnectController = {},
                onSaveExits = {}, onTogglePulseAnimation = {}, onChangeThemeMode = {},
                onToggleDynamicColor = {}, onSetControllerDialog = {}, onSetExitsDialog = {},
                onSetScopeDialog = {}
            )
        )
    }
}
