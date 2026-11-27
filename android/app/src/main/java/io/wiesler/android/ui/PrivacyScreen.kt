package io.wiesler.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
    var thresholdInput by remember(state.preferences.uploadThresholdKib) {
        mutableStateOf(state.preferences.uploadThresholdKib.toString())
    }
    var showJsonPreview by remember { mutableStateOf(false) }

    val activeCards = remember(state.activePrivacyRun) {
        state.activePrivacyRun?.let { PrivacyInspector.activeCards(it) }.orEmpty()
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
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
            ExpressivePageHeader(title = "隐私检查")
        }

        item(key = "active_privacy") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DNS 与出口检查", style = MaterialTheme.typography.titleLarge)
                    if (state.activePrivacyBusy) {
                        LoadingIndicator(modifier = Modifier.size(26.dp))
                    } else if (state.activePrivacyRun != null) {
                        Text(
                            text = timeFormat.format(Date(state.activePrivacyRun.startedAt)),
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = onRunActivePrivacy,
                    enabled = !state.activePrivacyBusy,
                    colors = wieslerPrimaryButtonColors(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.activePrivacyBusy) "探测中…" else "开始检查")
                }
                if (activeCards.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        activeCards.forEachIndexed { idx, card ->
                            PrivacyCheckItemRow(card)
                            if (idx < activeCards.lastIndex) {
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }

        item(key = "snapshot_privacy") {
            val findings = state.privacyReport?.findings.orEmpty()
            val summary = state.privacySummary
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("连接核查", style = MaterialTheme.typography.titleLarge)
                    StatusPill(
                        text = "${findings.size} 条线索",
                        containerColor = Color.Transparent,
                        contentColor = if (findings.isNotEmpty()) {
                            LocalWieslerColors.current.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        showDot = findings.isNotEmpty()
                    )
                }

                if (summary != null) {
                    Text(summary.title, style = MaterialTheme.typography.titleMedium)
                    summary.checks.forEachIndexed { idx, check ->
                        if (idx == 0) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        PrivacyCheckItemRow(check)
                        if (idx < summary.checks.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                } else {
                    Text(
                        text = "连接控制器后自动核查",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全代理预期", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = state.preferences.expectProxy, onCheckedChange = onUpdateExpectProxy)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        },
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text("应用")
                    }
                }

                if (findings.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        findings.take(30).forEachIndexed { idx, finding ->
                            ExpandableFindingRow(finding)
                            if (idx < minOf(29, findings.lastIndex)) {
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
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
                    Text("系统网络与 HTTPS", style = MaterialTheme.typography.titleLarge)
                    IconButton(
                        onClick = onRefreshSystemNetwork,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        text = state.systemNetworkSnapshot.ifBlank { "读取中…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.httpsProbeResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onRunHttpsProbe,
                        enabled = !state.httpsProbeBusy,
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(if (state.httpsProbeBusy) "探测中" else "HTTPS 204")
                    }
                }
            }
        }

        item(key = "environment") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设备环境", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onRunEnvironmentCheck) { Text("自检") }
                }
                val env = state.deviceEnvironment
                if (env == null) {
                    Text(
                        "点击自检读取语言、时区、屏幕与系统信息",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnvRow("语言", env.languages.joinToString("、").ifBlank { "未知" })
                        EnvRow("时区", env.timezone)
                        EnvRow("屏幕", env.screenSummary)
                        EnvRow("系统", env.androidVersion)
                        EnvRow("Private DNS", env.privateDnsSummary)
                    }
                }
            }
        }

        item(key = "export") {
            ExpressiveSectionCard {
                Text("导出", style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onShareTextReport,
                        colors = wieslerPrimaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("分享摘要")
                    }
                    FilledTonalButton(
                        onClick = onShareJsonReport,
                        colors = wieslerSecondaryButtonColors(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("分享 JSON")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            if (!showJsonPreview) onGeneratePrivacyJson()
                            showJsonPreview = !showJsonPreview
                        }
                    ) {
                        Text(if (showJsonPreview) "收起预览" else "预览 JSON")
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrivacyCheckItemRow(card: PrivacyCheckCard) {
    val semantic = LocalWieslerColors.current
    val accent = when (card.state) {
        "attention" -> semantic.warning
        "observed" -> semantic.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(card.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusPill(
                text = when (card.state) {
                    "attention" -> "需核对"
                    "observed" -> "正常"
                    else -> "待确认"
                },
                containerColor = Color.Transparent,
                contentColor = accent,
                showDot = true
            )
        }
        Text(card.result, style = MaterialTheme.typography.titleMedium)
        Text(card.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandableFindingRow(finding: PrivacyFinding) {
    val semantic = LocalWieslerColors.current
    var expanded by remember(finding.flow.id, finding.title) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(finding.title, style = MaterialTheme.typography.titleMedium, color = semantic.warning)
                Text(
                    "${finding.flow.app} · ${finding.flow.destination}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(finding.detail, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${finding.flow.protocol} · ${finding.flow.route} · ${finding.flow.rule}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
