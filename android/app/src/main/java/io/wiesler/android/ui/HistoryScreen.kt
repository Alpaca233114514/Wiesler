package io.wiesler.android.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.wiesler.android.StoredSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryScreen(
    state: WieslerUiState,
    onPickDatabase: () -> Unit,
    onSelectSession: (StoredSession) -> Unit,
    onGenerateReport: () -> Unit,
    onShareHistoryReport: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }

    val bottomClearance = LocalBottomBarClearance.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            ExpressivePageHeader(
                kicker = "SESSION ARCHIVE",
                title = "历史诊断",
                subtitle = "只读打开 Wiesler CLI SQLite 证据库并生成会话报告"
            )
        }

        item(key = "sqlite_picker") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("SQLite 证据库（只读）", style = MaterialTheme.typography.titleLarge)
                    }
                    if (state.historyBusy) {
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                Text(
                    text = state.historyStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onPickDatabase,
                    enabled = !state.historyBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("选择 .sqlite3 / .db 证据库")
                }

                val db = state.importedDb
                if (db != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("已载入：${db.displayName}（${db.sessions.size} 个会话）", style = MaterialTheme.typography.titleMedium)
                            if (db.sessions.isEmpty()) {
                                Text("该证据库目前没有会话记录。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    db.sessions.take(20).forEach { session ->
                                        val selected = state.selectedHistorySession?.sessionId == session.sessionId
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onSelectSession(session) },
                                            label = {
                                                Text("${timeFmt.format(Date(session.startedAtUtc))} · ${session.eventCount} 事件 · ${session.sessionId.take(12)}")
                                            }
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = onGenerateReport,
                                    enabled = state.selectedHistorySession != null && !state.historyBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("生成所选会话诊断报告")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.historyReportMarkdown.isNotBlank()) {
            item(key = "history_report") {
                ExpressiveSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("诊断报告", style = MaterialTheme.typography.titleLarge)
                        }
                        FilledTonalButton(onClick = onShareHistoryReport) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("分享")
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        SelectionContainer {
                            Text(
                                text = state.historyReportMarkdown,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().padding(14.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "local_persistence_summary") {
            ExpressiveSectionCard {
                Text("本机实时缓存概览", style = MaterialTheme.typography.titleLarge)
                Text(
                    "手机端重启自动恢复最近 60 个全局/转发流量采样点与最近 100 条请求日志，数据仅存放在应用私有目录且禁用系统云备份。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricChipTile(
                        label = "全局样本",
                        value = "${state.globalTrend.count { it != null }} / 60",
                        compact = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    MetricChipTile(
                        label = "图谱回放帧",
                        value = "${state.studioFrameCount} / 60",
                        compact = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    MetricChipTile(
                        label = "请求日志",
                        value = "${state.requestLogs.size} / 100",
                        compact = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}
