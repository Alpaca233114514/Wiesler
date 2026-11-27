package io.wiesler.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

data class PrivacyPolicy(
    val expectProxy: Boolean = false,
    val uploadBytesPerSecond: Long = 1024L * 1024L
)

data class PrivacyFinding(
    val kind: String,
    val status: String,
    val title: String,
    val detail: String,
    val flow: StudioFlow,
    val observedAt: Long
)

data class PrivacyCheckCard(
    val name: String,
    val state: String,
    val result: String,
    val reason: String,
    val action: String = ""
)

data class PrivacyCoverage(
    val connections: Int,
    val unknownRoutes: Int,
    val measuredUploads: Int
)

data class PrivacyReport(
    val observedAt: Long?,
    val source: String,
    val error: String?,
    val coverage: PrivacyCoverage,
    val policy: PrivacyPolicy,
    val findings: List<PrivacyFinding>
)

data class PrivacySummary(
    val title: String,
    val detail: String,
    val checks: List<PrivacyCheckCard>
)

data class ActiveDnsInspection(
    val interfaceName: String?,
    val isVpn: Boolean,
    val mtu: Int?,
    val privateDnsActive: Boolean,
    val privateDnsServer: String?,
    val dnsServers: List<String>,
    val probeDomain: String = "example.com",
    val resolvedAddresses: List<String> = emptyList(),
    val probeElapsedMs: Long? = null,
    val probeError: String? = null
)

data class ActivePrivacyRun(
    val startedAt: Long,
    val dns: ActiveDnsInspection? = null,
    val dnsError: String? = null,
    val systemEgress: EgressAddress? = null,
    val systemError: String? = null,
    val proxyEgress: EgressAddress? = null,
    val proxyError: String? = null
)

data class DeviceEnvironmentInfo(
    val languages: List<String>,
    val timezone: String,
    val screenSummary: String,
    val androidVersion: String,
    val privateDnsSummary: String
)

object PrivacyInspector {
    fun analyze(frame: StudioFrame?, policy: PrivacyPolicy = PrivacyPolicy(), source: String = "mihomo"): PrivacyReport {
        val flows = frame?.flows.orEmpty()
        val findings = ArrayList<PrivacyFinding>()
        val observedAt = frame?.wallTime
        for (flow in flows) {
            val port = flow.destinationPort.toIntOrNull()
            // 53 端口仅作线索，不能单独证明 DNS 明文内容或物理网卡出口。
            // Port 53 is a clue only; it proves neither DNS payload nor physical interface.
            if (port == 53) {
                findings.add(
                    PrivacyFinding(
                        kind = "network",
                        status = "unverified",
                        title = "DNS 端口连接",
                        detail = "目标端口 53，具体 DNS 服务器与 Private DNS 状态请查看上方主动网络检查。",
                        flow = flow,
                        observedAt = observedAt ?: 0L
                    )
                )
            }
            if (policy.expectProxy && flow.routing == FlowRouting.DIRECT) {
                findings.add(
                    PrivacyFinding(
                        kind = "network",
                        status = "policy_mismatch",
                        title = "DIRECT 与预期代理不符",
                        detail = "控制器报告该连接走 DIRECT，与当前「预期所有连接经代理转发」不一致。",
                        flow = flow,
                        observedAt = observedAt ?: 0L
                    )
                )
            }
            val tx = flow.tx
            if (policy.uploadBytesPerSecond > 0 && tx != null && tx.isFinite() && tx > policy.uploadBytesPerSecond) {
                findings.add(
                    PrivacyFinding(
                        kind = "connection",
                        status = "signal",
                        title = "上传速率超过阈值",
                        detail = "当前上传 ${formatBytes(tx)}/s，已超过阈值 ${formatBytes(policy.uploadBytesPerSecond.toDouble())}/s。",
                        flow = flow,
                        observedAt = observedAt ?: 0L
                    )
                )
            }
        }
        return PrivacyReport(
            observedAt = observedAt,
            source = if (frame == null) "none" else source,
            error = frame?.error,
            coverage = PrivacyCoverage(
                connections = flows.size,
                unknownRoutes = flows.count { it.routing == FlowRouting.UNKNOWN },
                measuredUploads = flows.count { it.tx != null && it.tx.isFinite() }
            ),
            policy = policy,
            findings = findings
        )
    }

    fun summarize(report: PrivacyReport): PrivacySummary {
        val dns = report.findings.filter { it.title == "DNS 端口连接" }
        val bypass = report.findings.filter { it.status == "policy_mismatch" }
        val uploads = report.findings.filter { it.kind == "connection" }
        val hasSample = report.observedAt != null
        val partial = report.error != null
        val total = report.coverage.connections
        val measured = report.coverage.measuredUploads
        val checks = listOf(
            PrivacyCheckCard(
                name = "53 端口连接",
                state = "unknown",
                result = when {
                    !hasSample -> "等待连接采样"
                    dns.isNotEmpty() -> "${dns.size} 条连接使用 53 端口"
                    else -> "未发现 53 端口连接"
                },
                reason = "连接快照仅包含端口信息；Android 非 root 且不接管 VPN，无法抓取底层明文 DNS 报文。",
                action = "使用上方「开始主动检查」核对系统 DNS、Private DNS 与出口对比。"
            ),
            PrivacyCheckCard(
                name = "代理路径",
                state = if (bypass.isNotEmpty()) "attention" else "unknown",
                result = when {
                    bypass.isNotEmpty() -> "${bypass.size} 条连接不符合代理预期"
                    !report.policy.expectProxy -> "未开启全代理核查"
                    !hasSample -> "等待采样"
                    partial || report.coverage.unknownRoutes > 0 -> "部分连接路径待确认"
                    else -> "已观测连接均符合预期"
                },
                reason = when {
                    bypass.isNotEmpty() -> "控制器对这些连接报告了 DIRECT 直连。"
                    !report.policy.expectProxy -> "当前未要求全部连接走代理。"
                    else -> "其中 ${report.coverage.unknownRoutes} 条连接缺少路由标记。"
                },
                action = if (bypass.isNotEmpty()) "核对对应应用的分流规则，或关闭全代理核查。" else "需要检查全代理时可开启「预期所有连接经代理转发」。"
            ),
            PrivacyCheckCard(
                name = "上传速率",
                state = when {
                    uploads.isNotEmpty() -> "attention"
                    measured == 0 || partial -> "unknown"
                    else -> "observed"
                },
                result = when {
                    uploads.isNotEmpty() -> "${uploads.size} 条连接上传超过阈值"
                    measured == 0 -> "等待连续流量样本"
                    else -> "均在上传阈值内"
                },
                reason = if (uploads.isNotEmpty()) "检测到高于阈值的持续上传流量。" else "当前 $total 条连接中 $measured 条有实时速率计数。",
                action = if (uploads.isNotEmpty()) "确认对应应用是否正在同步或上传。" else "可在下方调整上传阈值。"
            )
        )
        val title = when {
            !hasSample -> "等待连接数据"
            bypass.isNotEmpty() || uploads.isNotEmpty() -> "发现需要核对的连接"
            partial -> "采集不完整"
            dns.isNotEmpty() -> "发现 53 端口连接"
            else -> "连接路径无异常"
        }
        val detail = if (!hasSample) {
            "连接手机 Mihomo 控制器后显示连接隐私汇总。"
        } else {
            "$total 条连接 · ${bypass.size} 条直连差异 · ${uploads.size} 条上传超阈值${if (partial) "（部分采集失败）" else ""}"
        }
        return PrivacySummary(title, detail, checks)
    }

    fun activeCards(run: ActivePrivacyRun, localTimeZoneId: String = TimeZone.getDefault().id): List<PrivacyCheckCard> {
        val cards = ArrayList<PrivacyCheckCard>()
        val dns = run.dns
        if (dns != null) {
            val serversText = if (dns.dnsServers.isEmpty()) "未读取到系统 DNS" else dns.dnsServers.joinToString("、")
            val privateDns = if (dns.privateDnsActive) {
                "Private DNS 已启用${dns.privateDnsServer?.let { "（$it）" } ?: ""}"
            } else {
                "Private DNS 未启用"
            }
            val probeSummary = when {
                dns.probeError != null -> "解析 ${dns.probeDomain} 失败：${dns.probeError}"
                dns.resolvedAddresses.isNotEmpty() -> "解析 ${dns.probeDomain} → ${dns.resolvedAddresses.joinToString("、")}（${dns.probeElapsedMs ?: 0} ms）"
                else -> "未返回解析地址"
            }
            cards.add(
                PrivacyCheckCard(
                    name = "系统 DNS 与解析",
                    state = if (dns.probeError != null || !dns.privateDnsActive && !dns.isVpn) "attention" else "observed",
                    result = serversText,
                    reason = "接口：${dns.interfaceName ?: "未知"}${if (dns.isVpn) "（VPN 活跃）" else ""} · MTU ${dns.mtu ?: "未知"}\n$privateDns\n$probeSummary"
                )
            )
        } else {
            cards.add(
                PrivacyCheckCard(
                    name = "系统 DNS 检查",
                    state = "unknown",
                    result = if (run.dnsError != null) "检测失败" else "等待检测",
                    reason = run.dnsError ?: "读取 Android LinkProperties DNS 配置并测试解析。"
                )
            )
        }

        val routes = listOf(
            Triple("系统路径公网出口", run.systemEgress, run.systemError),
            Triple("代理路径公网出口", run.proxyEgress, run.proxyError)
        )
        for ((label, egress, err) in routes) {
            cards.add(
                PrivacyCheckCard(
                    name = label,
                    state = if (egress != null) "observed" else "unknown",
                    result = egress?.ip ?: if (err != null) "未取得结果" else "尚未探测",
                    reason = egress?.let {
                        val loc = listOfNotNull(it.location?.country, it.location?.city, it.location?.isp, it.location?.timezone)
                            .joinToString(" · ")
                            .ifBlank { "地理位置未知" }
                        loc + (it.locationError?.let { le -> "；归属地查询失败：$le" } ?: "")
                    } ?: (err ?: "通过 HTTPS 查询公网出口与归属地。")
                )
            )
        }

        val sys = run.systemEgress
        val proxy = run.proxyEgress
        if (sys != null && proxy != null) {
            val same = sys.ip == proxy.ip
            cards.add(
                PrivacyCheckCard(
                    name = "公网出口对比",
                    state = if (same) "observed" else "attention",
                    result = if (same) "两条探测路径出口相同" else "两条探测路径出口不同",
                    reason = if (same) {
                        "系统路径与代理路径返回相同公网 IP，当前流量可能已由系统 VPN / TUN 接管。"
                    } else {
                        "系统路径与代理路径返回不同公网 IP，当前处于分流或直连与代理并存状态。"
                    }
                )
            )
        }

        val remoteZone = proxy?.location?.timezone ?: sys?.location?.timezone
        if (!remoteZone.isNullOrBlank()) {
            val localTz = TimeZone.getTimeZone(localTimeZoneId)
            val remoteTz = TimeZone.getTimeZone(remoteZone)
            val localOffset = localTz.getOffset(run.startedAt)
            val remoteOffset = remoteTz.getOffset(run.startedAt)
            val sameOffset = localOffset == remoteOffset
            cards.add(
                PrivacyCheckCard(
                    name = "时区与公网出口",
                    state = if (sameOffset) "observed" else "attention",
                    result = if (sameOffset) "时区偏移与出口一致" else "时区偏移与出口不同",
                    reason = "本机 $localTimeZoneId（${formatUtcOffset(localOffset)}）；出口 $remoteZone（${formatUtcOffset(remoteOffset)}）。"
                )
            )
        }
        return cards
    }

    fun formatUtcOffset(offsetMs: Int): String {
        val totalMinutes = offsetMs / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(totalMinutes)
        return String.format(Locale.ROOT, "UTC%s%02d:%02d", sign, abs / 60, abs % 60)
    }

    fun exportJson(
        report: PrivacyReport,
        summary: PrivacySummary,
        activeRun: ActivePrivacyRun?,
        env: DeviceEnvironmentInfo?,
        rfDiagnostics: RfDiagnosticsSnapshot? = null
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("platform", "android")
        root.put("observedAt", report.observedAt ?: JSONObject.NULL)
        root.put("source", report.source)
        root.put("summary", JSONObject().put("title", summary.title).put("detail", summary.detail))
        if (rfDiagnostics != null) {
            root.put("rfDiagnostics", rfDiagnostics.toJsonObject())
        }
        root.put("policy", JSONObject()
            .put("expectProxy", report.policy.expectProxy)
            .put("uploadBytesPerSecond", report.policy.uploadBytesPerSecond))
        root.put("coverage", JSONObject()
            .put("connections", report.coverage.connections)
            .put("unknownRoutes", report.coverage.unknownRoutes)
            .put("measuredUploads", report.coverage.measuredUploads)
            .put("packetDnsCapture", false))
        root.put("findings", JSONArray().apply {
            report.findings.forEach { finding ->
                put(JSONObject()
                    .put("kind", finding.kind)
                    .put("status", finding.status)
                    .put("title", finding.title)
                    .put("detail", finding.detail)
                    .put("connectionId", finding.flow.id)
                    .put("app", finding.flow.app)
                    .put("destination", finding.flow.destination)
                    .put("route", finding.flow.route))
            }
        })
        if (activeRun != null) {
            val activeJson = JSONObject().put("startedAt", activeRun.startedAt)
            activeRun.dns?.let { dns ->
                activeJson.put("dns", JSONObject()
                    .put("interface", dns.interfaceName ?: JSONObject.NULL)
                    .put("isVpn", dns.isVpn)
                    .put("privateDnsActive", dns.privateDnsActive)
                    .put("privateDnsServer", dns.privateDnsServer ?: JSONObject.NULL)
                    .put("servers", JSONArray(dns.dnsServers))
                    .put("probeDomain", dns.probeDomain)
                    .put("resolvedAddresses", JSONArray(dns.resolvedAddresses))
                    .put("probeElapsedMs", dns.probeElapsedMs ?: JSONObject.NULL)
                    .put("probeError", dns.probeError ?: JSONObject.NULL))
            }
            activeRun.systemEgress?.let { e ->
                activeJson.put("systemEgress", JSONObject()
                    .put("ip", e.ip)
                    .put("provider", e.provider)
                    .put("location", e.location?.summary() ?: JSONObject.NULL)
                    .put("timezone", e.location?.timezone ?: JSONObject.NULL))
            }
            activeRun.proxyEgress?.let { e ->
                activeJson.put("proxyEgress", JSONObject()
                    .put("ip", e.ip)
                    .put("provider", e.provider)
                    .put("location", e.location?.summary() ?: JSONObject.NULL)
                    .put("timezone", e.location?.timezone ?: JSONObject.NULL))
            }
            root.put("activeInspection", activeJson)
        }
        if (env != null) {
            root.put("environment", JSONObject()
                .put("scope", "当前 Wiesler Android 客户端可见系统环境")
                .put("languages", JSONArray(env.languages))
                .put("timezone", env.timezone)
                .put("screen", env.screenSummary)
                .put("androidVersion", env.androidVersion)
                .put("privateDns", env.privateDnsSummary))
        }
        return root.toString(2)
    }
}
