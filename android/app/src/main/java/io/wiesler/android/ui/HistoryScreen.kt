package io.wiesler.android.ui

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
            ExpressivePageHeader(title = "历史")
        }

        item(key = "sqlite_picker") {
            ExpressiveSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SQLite 证据库", style = MaterialTheme.typography.titleLarge)
                    if (state.historyBusy) {
                        LoadingIndicator(modifier = Modifier.size(26.dp))
                    }
                }
                Button(
                    onClick = onPickDatabase,
                    enabled = !state.historyBusy,
                    colors = wieslerPrimaryButtonColors(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打开 .sqlite3 / .db 文件")
                }

                val db = state.importedDb
                if (db != null) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "${db.displayName} · ${db.sessions.size} 会话",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (db.sessions.isEmpty()) {
                            Text(
                                text = "无会话记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                db.sessions.take(20).forEach { session ->
                                    val selected = state.selectedHistorySession?.sessionId == session.sessionId
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectSession(session) },
                                        label = {
                                            Text("${timeFmt.format(Date(session.startedAtUtc))} · ${session.eventCount} 事件")
                                        }
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = onGenerateReport,
                                enabled = state.selectedHistorySession != null && !state.historyBusy,
                                colors = wieslerSecondaryButtonColors(),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("生成诊断报告")
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
                        Text("诊断报告", style = MaterialTheme.typography.titleLarge)
                        FilledTonalButton(
                            onClick = onShareHistoryReport,
                            colors = wieslerSecondaryButtonColors(),
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
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

        item(key = "local_persistence_summary") {
            ExpressiveSectionCard {
                Text("本机缓存", style = MaterialTheme.typography.titleLarge)
                MetricStatStrip(
                    items = listOf(
                        StatMetricItem(
                            label = "全局样本",
                            value = "${state.globalTrend.count { it != null }}"
                        ),
                        StatMetricItem(
                            label = "图谱帧",
                            value = "${state.studioFrameCount}"
                        ),
                        StatMetricItem(
                            label = "请求日志",
                            value = "${state.requestLogs.size}"
                        )
                    ),
                    compact = true
                )
            }
        }
    }
}
