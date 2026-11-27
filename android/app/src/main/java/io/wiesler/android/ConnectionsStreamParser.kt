package io.wiesler.android

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

data class StreamedConnectionsResult(
    val flows: List<StudioFlow>,
    val counters: List<RoutedCounter>,
    val exits: List<String>,
    val downTotal: Long?,
    val upTotal: Long?
)

/**
 * 基于 Android 原生 JsonReader 的单遍流式 Mihomo /connections 解析器：
 * 直接从网络流读取字段构造 StudioFlow 与 RoutedCounter，避免构建数 MB 中间字符串与 JSONObject DOM 树，
 * 显著降低连接较多时的堆内存峰值与 GC 停顿。
 *
 * Single-pass streaming Mihomo /connections parser backed by android.util.JsonReader to eliminate
 * multi-megabyte intermediate String and JSONObject DOM allocations.
 */
object ConnectionsStreamParser {
    const val DEFAULT_MAX_BYTES = 4_000_000L

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var totalRead = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0 && ++totalRead > maxBytes) error("控制器快照过大")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = delegate.read(b, off, len)
            if (count > 0) {
                totalRead += count
                if (totalRead > maxBytes) error("控制器快照过大")
            }
            return count
        }

        override fun close() = delegate.close()
    }

    fun parse(input: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): StreamedConnectionsResult {
        val bounded = BoundedInputStream(BufferedInputStream(input, 16_384), maxBytes)
        JsonReader(InputStreamReader(bounded, Charsets.UTF_8)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) error("不是有效的 Mihomo 快照")
            var hasConnectionsKey = false
            var downTotal: Long? = null
            var upTotal: Long? = null
            val flows = ArrayList<StudioFlow>(64)
            val counters = ArrayList<RoutedCounter>(64)
            val exits = HashSet<String>(16)

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "downloadTotal" -> downTotal = readOptionalLong(reader)?.takeIf { it >= 0 }
                    "uploadTotal" -> upTotal = readOptionalLong(reader)?.takeIf { it >= 0 }
                    "connections" -> {
                        hasConnectionsKey = true
                        when (reader.peek()) {
                            JsonToken.NULL -> reader.nextNull()
                            JsonToken.BEGIN_ARRAY -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    parseConnectionObject(reader, flows, counters, exits)
                                }
                                reader.endArray()
                            }
                            else -> error("连接列表格式无效")
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (!hasConnectionsKey) error("不是有效的 Mihomo 快照")
            return StreamedConnectionsResult(
                flows = flows,
                counters = counters,
                exits = exits.sorted(),
                downTotal = downTotal,
                upTotal = upTotal
            )
        }
    }

    private fun parseConnectionObject(
        reader: JsonReader,
        flows: MutableList<StudioFlow>,
        counters: MutableList<RoutedCounter>,
        exits: MutableSet<String>
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        var id = ""
        var start = ""
        var download = -1L
        var upload = -1L
        var rule = ""
        var rulePayload = ""
        var firstChain: String? = null
        val chainBuilder = StringBuilder()

        var host = ""
        var process = ""
        var type = ""
        var sourceIp = ""
        var sourcePort = ""
        var destIp = ""
        var destPort = ""
        var network = ""

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readOptionalString(reader)
                "start" -> start = readOptionalString(reader)
                "download" -> download = readOptionalLong(reader) ?: -1L
                "upload" -> upload = readOptionalLong(reader) ?: -1L
                "rule" -> rule = readOptionalString(reader)
                "rulePayload" -> rulePayload = readOptionalString(reader)
                "chains" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val elem = readOptionalString(reader)
                            if (elem.isNotBlank()) {
                                if (firstChain == null) firstChain = elem
                                if (chainBuilder.isNotEmpty()) chainBuilder.append(" → ")
                                chainBuilder.append(elem)
                            }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                "metadata" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "host" -> host = readOptionalString(reader)
                                "process" -> process = readOptionalString(reader)
                                "type" -> type = readOptionalString(reader)
                                "sourceIP", "source_ip" -> {
                                    val v = readOptionalString(reader)
                                    if (sourceIp.isBlank() && v.isNotBlank()) sourceIp = v
                                }
                                "sourcePort", "source_port" -> {
                                    val v = readOptionalString(reader)
                                    if (sourcePort.isBlank() && v.isNotBlank()) sourcePort = v
                                }
                                "destinationIP", "destination_ip" -> {
                                    val v = readOptionalString(reader)
                                    if (destIp.isBlank() && v.isNotBlank()) destIp = v
                                }
                                "destinationPort", "destination_port" -> {
                                    val v = readOptionalString(reader)
                                    if (destPort.isBlank() && v.isNotBlank()) destPort = v
                                }
                                "network" -> network = readOptionalString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val resolvedHost = host.ifBlank { destIp.ifBlank { "未知目标" } }
        val resolvedProcess = process.ifBlank { type.ifBlank { "未知应用" } }
        val resolvedRoute = if (chainBuilder.isEmpty()) "未知" else chainBuilder.toString()
        val resolvedRule = when {
            rule.isNotBlank() && rulePayload.isNotBlank() -> "$rule $rulePayload"
            rule.isNotBlank() -> rule
            rulePayload.isNotBlank() -> rulePayload
            else -> "未知"
        }

        flows.add(
            StudioFlow(
                id = id,
                start = start,
                app = resolvedProcess,
                host = resolvedHost,
                source = studioEndpoint(sourceIp, sourcePort),
                destination = studioEndpoint(destIp.ifBlank { resolvedHost }, destPort),
                protocol = network.uppercase(Locale.ROOT).ifBlank { "未知" },
                route = resolvedRoute,
                rule = resolvedRule,
                download = download,
                upload = upload,
                raw = ""
            )
        )
        counters.add(RoutedCounter(id, start, firstChain, download, upload))
        if (firstChain != null && eligibleExit(firstChain)) {
            exits.add(firstChain)
        }
    }

    private fun readOptionalString(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            ""
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> {
            reader.skipValue()
            ""
        }
    }

    private fun readOptionalLong(reader: JsonReader): Long? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        JsonToken.NUMBER -> runCatching { reader.nextLong() }.getOrNull()
        JsonToken.STRING -> reader.nextString().toLongOrNull()
        else -> {
            reader.skipValue()
            null
        }
    }
}
