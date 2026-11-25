package io.wiesler.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.wiesler.android.PrivacyCheckCard
import io.wiesler.android.PrivacyFinding
import io.wiesler.android.PrivacyInspector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrivacyScreen(
    state: WieslerUiState,
    onRunActivePrivacy: () -> Unit,
    onUpdateExpectProxy: (Boolean) -> Unit,
    onUpdateUploadThresholdKib: (Int) -> Unit,
    onRunHttpsProbe: () -> Unit,
    onRefreshSystemNetwork: () -> Unit,
    onRunEnvironmentCheck: () -> Unit,
    onGeneratePrivacyJson: () -> Unit,
    onShareTextReport: () -> Unit,
    onShareJsonReport: () -> Unit
) {
    val semantic = LocalWieslerColors.current
    var thresholdInput by remember(state.preferences.uploadThresholdKib) {
        mutableStateOf(state.preferences.uploadThresholdKib.toString())
    }
    var shareMenuOpen by remember { mutableStateOf(false) }
    var showJsonPreview by remember { mutableStateOf(false) }

    val activeCards = remember(state.activePrivacyRun) {
        state.activePrivacyRun?.let { PrivacyInspector.activeCards(it) }.orEmpty()
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }

    val bottomClearance = LocalBottomBarClearance.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            ExpressivePageHeader(
                kicker = "PRIVACY & DNS INSPECTOR",
                title = "隐私与 DNS 检查",
                subtitle = "系统 DNS、双路出口对比、连接策略核查与环境自检"
            )
        }

        item(key = "active_privacy") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("主动 DNS 与公网出口检查", style = MaterialTheme.typography.titleLarge)
                    }
                    if (state.activePrivacyBusy) {
                        LoadingIndicator(modifier = Modifier.size(34.dp))
                    }
                }
                Text(
                    text = state.activePrivacyRun?.let {
                        "上次实测 ${timeFormat.format(Date(it.startedAt))} · 读取系统 LinkProperties DNS、测试解析 example.com 并对比系统/代理公网出口与时区。"
                    } ?: "读取活动网卡 DNS 与 Private DNS，实测域名解析并对比双路公网出口与时区偏移。注：Android 非 root 且不接管 VPN，无法抓取他应用明文 DNS 底层报文。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRunActivePrivacy,
                    enabled = !state.activePrivacyBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.activePrivacyBusy) "正在并发探测 DNS 与双路出口…" else "开始主动网络检查")
                }
                if (activeCards.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeCards.forEach { card ->
                            PrivacyCheckItemCard(card)
                        }
                    }
                }
            }
        }

        item(key = "snapshot_privacy") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("连接快照隐私核查", style = MaterialTheme.typography.titleLarge)
                    }
                    StatusPill(
                        text = "${state.privacyReport?.findings?.size ?: 0} 条线索",
                        containerColor = if ((state.privacyReport?.findings?.size ?: 0) > 0) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if ((state.privacyReport?.findings?.size ?: 0) > 0) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                val summary = state.privacySummary
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(summary?.title ?: "等待连接数据", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = summary?.detail ?: "连接手机 Mihomo 控制器后自动核查 53 端口、代理预期与上传速率。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                summary?.checks?.forEach { check ->
                    PrivacyCheckItemCard(check)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("预期所有连接经代理转发", style = MaterialTheme.typography.titleMedium)
                        Text("开启后将把控制器报告的 DIRECT 直连标为待核对项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.preferences.expectProxy, onCheckedChange = onUpdateExpectProxy)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("上传告警阈值 (KiB/s)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val v = thresholdInput.toIntOrNull()
                            if (v != null && v in 1..1_048_576) onUpdateUploadThresholdKib(v)
                        }
                    ) {
                        Text("应用")
                    }
                }

                val findings = state.privacyReport?.findings.orEmpty()
                if (findings.isNotEmpty()) {
                    Text("待核对连接明细（${findings.size}）", style = MaterialTheme.typography.titleMedium)
                    findings.take(30).forEach { finding ->
                        ExpandableFindingCard(finding)
                    }
                }
            }
        }

        item(key = "system_and_https") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Http, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("系统网络快照与 HTTPS 探测", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedButton(onClick = onRefreshSystemNetwork) { Text("刷新") }
                }
                SelectionContainer {
                    Text(
                        text = state.systemNetworkSnapshot.ifBlank { "正在读取系统网络属性…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text("HTTPS 连通性（www.gstatic.com/generate_204）", style = MaterialTheme.typography.titleMedium)
                Text(state.httpsProbeResult, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick = onRunHttpsProbe,
                    enabled = !state.httpsProbeBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.httpsProbeBusy) "正在检查 HTTPS…" else "检查 HTTPS 连通性")
                }
            }
        }

        item(key = "environment") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("设备运行环境暴露面自检", style = MaterialTheme.typography.titleLarge)
                    }
                    FilledTonalButton(onClick = onRunEnvironmentCheck) {
                        Text("本地自检", maxLines = 1, softWrap = false)
                    }
                }
                val env = state.deviceEnvironment
                if (env == null) {
                    Text(
                        "点击「本地自检」读取当前客户端可见的系统语言、时区、屏幕密度、Android 版本与 Private DNS 状态。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("语言偏好：${env.languages.joinToString("、").ifBlank { "未知" }}", style = MaterialTheme.typography.bodyMedium)
                            Text("系统时区：${env.timezone}", style = MaterialTheme.typography.bodyMedium)
                            Text("屏幕特征：${env.screenSummary}", style = MaterialTheme.typography.bodyMedium)
                            Text("系统版本：${env.androidVersion}", style = MaterialTheme.typography.bodyMedium)
                            Text("Private DNS：${env.privateDnsSummary}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item(key = "export") {
            ExpressiveSectionCard {
                Text("诊断与隐私证据导出", style = MaterialTheme.typography.titleLarge)
                Text(
                    "支持一键分享纯文本诊断摘要，或导出与桌面端同结构的 JSON 隐私证据快照（不含密钥与响应正文）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    SplitButtonLayout(
                        leadingButton = {
                            SplitButtonDefaults.LeadingButton(
                                onClick = onShareTextReport
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("分享诊断报告")
                            }
                        },
                        trailingButton = {
                            SplitButtonDefaults.TrailingButton(
                                checked = shareMenuOpen,
                                onCheckedChange = { shareMenuOpen = it }
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "更多导出选项")
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = shareMenuOpen,
                        onDismissRequest = { shareMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("生成并预览 JSON 证据报告") },
                            onClick = {
                                shareMenuOpen = false
                                onGeneratePrivacyJson()
                                showJsonPreview = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("分享 JSON 隐私证据报告") },
                            onClick = {
                                shareMenuOpen = false
                                onShareJsonReport()
                            }
                        )
                    }
                }

                AnimatedVisibility(visible = showJsonPreview && state.exportedPrivacyJson.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        SelectionContainer {
                            Text(
                                text = state.exportedPrivacyJson,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyCheckItemCard(card: PrivacyCheckCard) {
    val semantic = LocalWieslerColors.current
    val accent = when (card.state) {
        "attention" -> semantic.warning
        "observed" -> semantic.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(card.name, style = MaterialTheme.typography.labelLarge, color = accent)
                StatusPill(
                    text = when (card.state) {
                        "attention" -> "需核对"
                        "observed" -> "已观测"
                        else -> "待确认"
                    },
                    containerColor = accent.copy(alpha = 0.16f),
                    contentColor = accent
                )
            }
            Text(card.result, style = MaterialTheme.typography.titleMedium)
            Text(card.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.action.isNotBlank()) {
                Text("建议：${card.action}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ExpandableFindingCard(finding: PrivacyFinding) {
    val semantic = LocalWieslerColors.current
    var expanded by remember(finding.flow.id, finding.title) { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, semantic.warning.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(finding.title, style = MaterialTheme.typography.titleMedium, color = semantic.warning)
                    Text("${finding.flow.app} · ${finding.flow.destination}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null
                )
            }
            Text(finding.detail, style = MaterialTheme.typography.bodyMedium)
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text("源地址：${finding.flow.source}", style = MaterialTheme.typography.labelMedium)
                    Text("目标地址：${finding.flow.destination}", style = MaterialTheme.typography.labelMedium)
                    Text("协议：${finding.flow.protocol}", style = MaterialTheme.typography.labelMedium)
                    Text("路由链：${finding.flow.route}", style = MaterialTheme.typography.labelMedium)
                    Text("规则：${finding.flow.rule}", style = MaterialTheme.typography.labelMedium)
                    Text("连接 ID：${finding.flow.id}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
