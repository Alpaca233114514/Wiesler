package io.wiesler.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredSession(
    val sessionId: String,
    val startedAtUtc: Long,
    val endedAtUtc: Long?,
    val status: String,
    val appVersion: String,
    val eventCount: Long
)

data class ImportedDatabaseInfo(
    val displayName: String,
    val localPath: String,
    val sessions: List<StoredSession>
)

object SqliteHistoryReader {
    private const val EXPECTED_SCHEMA_VERSION = 1
    private const val EXPECTED_APP_ID = 0x5749534c
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isValidHeader(header: ByteArray): Boolean {
        if (header.size < 72) return false
        for (i in SQLITE_MAGIC.indices) {
            if (header[i] != SQLITE_MAGIC[i]) return false
        }
        val userVersion = ((header[60].toInt() and 0xFF) shl 24) or
            ((header[61].toInt() and 0xFF) shl 16) or
            ((header[62].toInt() and 0xFF) shl 8) or
            (header[63].toInt() and 0xFF)
        val appId = ((header[68].toInt() and 0xFF) shl 24) or
            ((header[69].toInt() and 0xFF) shl 16) or
            ((header[70].toInt() and 0xFF) shl 8) or
            (header[71].toInt() and 0xFF)
        return userVersion == EXPECTED_SCHEMA_VERSION && appId == EXPECTED_APP_ID
    }

    fun importFromUri(context: Context, uri: Uri, displayName: String): Result<ImportedDatabaseInfo> = runCatching {
        val targetDir = File(context.cacheDir, "imported-dbs").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "evidence.sqlite3" }
        val localFile = File(targetDir, "latest-$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(72)
            val firstRead = input.read(header)
            if (firstRead < 72 || !isValidHeader(header)) {
                error("不是受支持的 Wiesler 证据库（需要 schema v1 / WISL 标识）")
            }
            localFile.outputStream().use { out ->
                out.write(header, 0, firstRead)
                val buf = ByteArray(8192)
                var total = firstRead.toLong()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 128L * 1024L * 1024L) error("证据库体积超过 128 MB 上限")
                    out.write(buf, 0, n)
                }
            }
        } ?: error("无法读取所选文件")
        val sessions = listSessions(localFile.absolutePath).getOrThrow()
        ImportedDatabaseInfo(displayName, localFile.absolutePath, sessions)
    }

    fun listSessions(path: String): Result<List<StoredSession>> = runCatching {
        openValidated(path).use { db ->
            val result = ArrayList<StoredSession>()
            val sql = """
                SELECT s.session_id, s.started_at_utc, s.ended_at_utc, s.status, s.app_version,
                       (SELECT COUNT(*) FROM events e WHERE e.session_id = s.session_id) AS cnt
                FROM sessions s
                ORDER BY s.started_at_utc DESC
            """.trimIndent()
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        StoredSession(
                            sessionId = cursor.getString(0),
                            startedAtUtc = cursor.getLong(1),
                            endedAtUtc = if (cursor.isNull(2)) null else cursor.getLong(2),
                            status = cursor.getString(3),
                            appVersion = cursor.getString(4),
                            eventCount = cursor.getLong(5)
                        )
                    )
                }
            }
            result
        }
    }

    fun diagnoseSession(path: String, session: StoredSession): Result<String> = runCatching {
        openValidated(path).use { db ->
            val events = ArrayList<String>()
            db.rawQuery(
                "SELECT json FROM events WHERE session_id = ? ORDER BY sequence ASC LIMIT 20000",
                arrayOf(session.sessionId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    events.add(cursor.getString(0))
                }
            }
            buildDiagnosticMarkdown(session, events)
        }
    }

    private fun openValidated(path: String): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = pragmaInt(db, "user_version")
            val appId = pragmaInt(db, "application_id")
            if (version != EXPECTED_SCHEMA_VERSION || appId != EXPECTED_APP_ID) {
                error("不是受支持的 Wiesler 数据库；保留原库，不自动迁移")
            }
            return db
        } catch (err: Throwable) {
            db.close()
            throw err
        }
    }

    private fun pragmaInt(db: SQLiteDatabase, name: String): Int =
        db.rawQuery("PRAGMA $name", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun buildDiagnosticMarkdown(session: StoredSession, eventsJson: List<String>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val kindCounts = LinkedHashMap<String, Int>()
        val controllerFailures = ArrayList<String>()
        val sourceGaps = ArrayList<String>()
        val routes = LinkedHashMap<String, Int>()
        val apps = LinkedHashMap<String, Int>()
        val hosts = LinkedHashMap<String, Int>()
        val appErrors = LinkedHashMap<String, Int>()
        var controllerVersion: String? = null
        var peakConnections = 0
        var lastDownloadTotal = 0L
        var lastUploadTotal = 0L

        for (raw in eventsJson) {
            val event = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val data = event.optJSONObject("data") ?: continue
            val kind = data.optString("kind").ifBlank { "Unknown" }
            kindCounts[kind] = (kindCounts[kind] ?: 0) + 1
            val payload = data.optJSONObject("payload")
            when (kind) {
                "ControllerVersion" -> controllerVersion = payload?.optString("version")?.ifBlank { null }
                "SnapshotObserved" -> {
                    val count = payload?.optInt("connection_count", 0) ?: 0
                    if (count > peakConnections) peakConnections = count
                    lastDownloadTotal = maxOf(lastDownloadTotal, payload?.optLong("download_total", 0L) ?: 0L)
                    lastUploadTotal = maxOf(lastUploadTotal, payload?.optLong("upload_total", 0L) ?: 0L)
                }
                "ConnectionObserved", "ConnectionUpdated" -> {
                    val conn = payload?.optJSONObject("connection")
                    val meta = conn?.optJSONObject("metadata")
                    val app = meta?.optString("process")?.ifBlank { null } ?: "未知应用"
                    val host = meta?.optString("host")?.ifBlank { null }
                        ?: meta?.optString("destination_ip")?.ifBlank { null }
                        ?: "未知目标"
                    val chains = conn?.optJSONArray("chains")
                    val exit = if (chains != null && chains.length() > 0) chains.optString(0) else "未知"
                    apps[app] = (apps[app] ?: 0) + 1
                    hosts[host] = (hosts[host] ?: 0) + 1
                    routes[exit] = (routes[exit] ?: 0) + 1
                }
                "ControllerUnavailable" -> {
                    val reason = payload?.optString("reason").orEmpty().ifBlank { "控制器不可用" }
                    if (controllerFailures.size < 10) controllerFailures.add(reason)
                }
                "SourceGap" -> {
                    val reason = payload?.optString("reason").orEmpty().ifBlank { "采样缺口" }
                    if (sourceGaps.size < 10) sourceGaps.add(reason)
                }
                "ApplicationObserved" -> {
                    val obs = payload?.optJSONObject("observation")
                    val errorCode = obs?.optString("error_code")?.takeIf { it.isNotBlank() && it != "null" }
                    val status = if (obs != null && !obs.isNull("http_status")) obs.optInt("http_status") else null
                    if (errorCode != null || (status != null && status >= 400)) {
                        val key = "${obs?.optString("application") ?: "app"} · ${errorCode ?: "HTTP $status"}"
                        appErrors[key] = (appErrors[key] ?: 0) + 1
                    }
                }
            }
        }

        return buildString {
            appendLine("# 历史会话诊断报告")
            appendLine()
            appendLine("- 会话 ID：`${session.sessionId}`")
            appendLine("- 开始时间：${fmt.format(Date(session.startedAtUtc))}")
            session.endedAtUtc?.let { appendLine("- 结束时间：${fmt.format(Date(it))}") }
            appendLine("- 状态：${session.status} · 采集版本 ${session.appVersion}")
            appendLine("- 事件总数：${session.eventCount}（已分析 ${eventsJson.size} 条）")
            controllerVersion?.let { appendLine("- 控制器版本：$it") }
            if (peakConnections > 0 || lastDownloadTotal > 0 || lastUploadTotal > 0) {
                appendLine("- 峰值并发连接：$peakConnections · 累计 ↓ ${formatBytes(lastDownloadTotal.toDouble())} / ↑ ${formatBytes(lastUploadTotal.toDouble())}")
            }
            appendLine()
            appendLine("## 事件分布")
            if (kindCounts.isEmpty()) {
                appendLine("- 无有效事件")
            } else {
                kindCounts.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    appendLine("- `$k`: $v")
                }
            }
            if (routes.isNotEmpty()) {
                appendLine()
                appendLine("## 主要出口分布")
                routes.entries.sortedByDescending { it.value }.take(8).forEach { (r, c) ->
                    appendLine("- $r：$c 次记录")
                }
            }
            if (apps.isNotEmpty()) {
                appendLine()
                appendLine("## 活跃应用（Top 8）")
                apps.entries.sortedByDescending { it.value }.take(8).forEach { (a, c) ->
                    appendLine("- $a：$c 次记录")
                }
            }
            if (hosts.isNotEmpty()) {
                appendLine()
                appendLine("## 常见目标（Top 8）")
                hosts.entries.sortedByDescending { it.value }.take(8).forEach { (h, c) ->
                    appendLine("- $h：$c 次记录")
                }
            }
            if (controllerFailures.isNotEmpty() || sourceGaps.isNotEmpty() || appErrors.isNotEmpty()) {
                appendLine()
                appendLine("## 异常与缺口")
                controllerFailures.forEach { appendLine("- 控制器异常：$it") }
                sourceGaps.forEach { appendLine("- 数据源缺口：$it") }
                appErrors.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    appendLine("- 应用错误 $k：$v 次")
                }
            }
        }.trim()
    }
}
