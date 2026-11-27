package io.wiesler.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 主动多卡蜂窝射频采集器（无需 root）：
 * - 优先消费系统推送的 SignalStrengthsListener / ServiceStateListener / CellInfoListener（API 31+）；
 * - 按自适应节拍触发 requestCellInfoUpdate()，平稳期降频省电并在已有新鲜回调时跳过冗余唤醒，检测到异常自动提速；
 * - 对瞬时重复信号回调与未变化的蜂窝校验状态做去抖过滤，减少主线程与 UI 重组负担。
 *
 * Adaptive multi-SIM RF collector prioritizing event-driven telephony callbacks with anomaly-aware active polling.
 */
class RfCellularCollector(
    private val context: Context,
    private val executor: Executor,
    private val onSample: (RfCellSample) -> Unit,
    private val onCellularLinkState: (validated: Boolean?, lostAtMs: Long?) -> Unit,
    private val onStatusError: (String) -> Unit
) {
    companion object {
        const val POLL_INTERVAL_MS = 6_000L
        const val MIN_POLL_INTERVAL_MS = 3_000L
        private const val SIGNAL_DEDUP_WINDOW_MS = 1_500L
        private const val FORCE_CELLINFO_STALE_MS = 18_000L
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        fun hasPermissions(context: Context): Boolean = REQUIRED_PERMISSIONS.all { perm ->
            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private data class SignalFingerprint(
        val rat: RfRat,
        val rsrpDbm: Int?,
        val rsrqDb: Int?,
        val sinrDb: Int?,
        val inService: Boolean
    )

    private data class SlotBinding(
        val slotIndex: Int,
        val subId: Int,
        val operatorName: String,
        val plmn: String,
        val manager: TelephonyManager,
        @Volatile var lastArfcn: Int? = null,
        @Volatile var lastCa: Boolean = false,
        @Volatile var lastInService: Boolean = true,
        @Volatile var lastCellInfoAtMs: Long = 0L,
        @Volatile var lastSignalAtMs: Long = 0L,
        @Volatile var lastSignalFingerprint: SignalFingerprint? = null,
        var callback31: Any? = null,
        @Suppress("DEPRECATION") var legacyListener: PhoneStateListener? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val bindings = mutableListOf<SlotBinding>()
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var steadyPollIntervalMs: Long = POLL_INTERVAL_MS
    @Volatile private var escalatedPollIntervalMs: Long = MIN_POLL_INTERVAL_MS
    @Volatile private var anomalyEscalated: Boolean = false
    @Volatile private var lastReportedValidated: Boolean? = null

    private fun effectivePollIntervalMs(): Long =
        (if (anomalyEscalated) escalatedPollIntervalMs else steadyPollIntervalMs)
            .coerceAtLeast(MIN_POLL_INTERVAL_MS)

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            pollAllSlots()
            mainHandler.postDelayed(this, effectivePollIntervalMs())
        }
    }

    val isRunning: Boolean get() = running.get()

    fun updateCadence(steadyMs: Long, escalatedMs: Long) {
        steadyPollIntervalMs = steadyMs.coerceAtLeast(MIN_POLL_INTERVAL_MS)
        escalatedPollIntervalMs = escalatedMs.coerceAtLeast(MIN_POLL_INTERVAL_MS)
    }

    fun setAnomalyEscalated(escalated: Boolean) {
        if (anomalyEscalated == escalated) return
        anomalyEscalated = escalated
        if (running.get() && escalated) {
            // 检测到异常立即切回高频主动刷新，避免错过干扰窗口。
            // Reschedule immediately on anomaly escalation so interference bursts are never missed.
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.postDelayed(pollRunnable, 400L)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermissions(context)) {
            onStatusError("缺少精确定位或读取手机状态权限，无法主动刷新蜂窝基带。")
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        val baseTm = context.getSystemService(TelephonyManager::class.java)
        val subMgr = context.getSystemService(SubscriptionManager::class.java)
        val activeSubs: List<SubscriptionInfo> = runCatching {
            subMgr?.activeSubscriptionInfoList.orEmpty()
        }.getOrElse { emptyList() }

        bindings.clear()
        if (activeSubs.isNotEmpty()) {
            activeSubs.sortedBy { it.simSlotIndex }.forEach { info ->
                val tm = baseTm.createForSubscriptionId(info.subscriptionId)
                val mcc = info.mccString.orEmpty()
                val mnc = info.mncString.orEmpty()
                val plmn = if (mcc.isNotBlank() && mnc.isNotBlank()) "$mcc$mnc" else tm.networkOperator.orEmpty()
                val op = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: tm.networkOperatorName?.takeIf { it.isNotBlank() }
                    ?: "卡 ${info.simSlotIndex + 1}"
                bindings.add(
                    SlotBinding(
                        slotIndex = info.simSlotIndex.coerceAtLeast(0),
                        subId = info.subscriptionId,
                        operatorName = op,
                        plmn = plmn,
                        manager = tm
                    )
                )
            }
        } else {
            val op = baseTm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "默认蜂窝卡"
            bindings.add(
                SlotBinding(
                    slotIndex = 0,
                    subId = SubscriptionManager.getDefaultDataSubscriptionId(),
                    operatorName = op,
                    plmn = baseTm.networkOperator.orEmpty(),
                    manager = baseTm
                )
            )
        }

        bindings.forEach { registerTelephonyListeners(it) }
        registerCellularLinkMonitor()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(pollRunnable)
        bindings.forEach { unregisterTelephonyListeners(it) }
        bindings.clear()
        unregisterCellularLinkMonitor()
        lastReportedValidated = null
    }

    @SuppressLint("MissingPermission")
    private fun pollAllSlots() {
        if (!running.get() || !hasPermissions(context)) return
        val nowMs = System.currentTimeMillis()
        val intervalMs = effectivePollIntervalMs()
        for (binding in bindings.toList()) {
            // 非异常提速期间，若系统推送回调已在近期回传新鲜数据且频点已知，则跳过本次主动基带唤醒以降低射频功耗。
            // When not escalated, skip redundant modem wakeup if a fresh callback arrived recently and ARFCN is known.
            val recentSignal = binding.lastSignalAtMs > 0L && (nowMs - binding.lastSignalAtMs) < (intervalMs * 6L / 10L)
            val recentCellInfo = binding.lastCellInfoAtMs > 0L && (nowMs - binding.lastCellInfoAtMs) < FORCE_CELLINFO_STALE_MS
            if (!anomalyEscalated && recentSignal && recentCellInfo && binding.lastArfcn != null) {
                continue
            }
            runCatching {
                binding.manager.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (!running.get()) return
                        handleCellInfoList(binding, cellInfo)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (!running.get()) return
                        Log.w("WieslerRF", "requestCellInfoUpdate failed on slot ${binding.slotIndex}: code=$errorCode ${detail?.message}")
                        // 主动刷新偶尔被厂商节流时，尝试读取最近基带缓存但严格保留时间戳交由引擎去重。
                        // On vendor throttling, fall back to cached list; timestamp dedup still prevents stale inflation.
                        runCatching { binding.manager.allCellInfo.orEmpty() }
                            .onSuccess { if (it.isNotEmpty()) handleCellInfoList(binding, it) }
                    }
                })
            }.onFailure { err ->
                Log.w("WieslerRF", "Poll slot ${binding.slotIndex} failed: ${err.javaClass.simpleName}: ${err.message}")
            }
        }
    }

    private fun handleCellInfoList(binding: SlotBinding, list: List<CellInfo>) {
        val nowMs = System.currentTimeMillis()
        binding.lastCellInfoAtMs = nowMs
        val registered = list.filter { it.isRegistered }
        val primary = registered.firstOrNull { it is CellInfoNr }
            ?: registered.firstOrNull { it is CellInfoLte }
            ?: registered.firstOrNull()

        if (primary == null) {
            if (!binding.lastInService) {
                onSample(
                    RfCellSample(
                        observedAtMs = nowMs,
                        slotIndex = binding.slotIndex,
                        subId = binding.subId,
                        operatorName = binding.operatorName,
                        plmn = binding.plmn,
                        rat = RfRat.NO_SIGNAL,
                        inService = false,
                        source = RfSampleSource.ACTIVE_CELL_INFO
                    )
                )
            }
            return
        }

        val sample = when (primary) {
            is CellInfoNr -> {
                val id = primary.cellIdentity as? CellIdentityNr
                val sig = primary.cellSignalStrength as? CellSignalStrengthNr
                val arfcn = cleanArfcn(id?.nrarfcn) ?: binding.lastArfcn
                if (arfcn != null) binding.lastArfcn = arfcn
                val plmn = buildPlmn(id?.mccString, id?.mncString).ifBlank { binding.plmn }
                val op = id?.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() } ?: binding.operatorName
                RfCellSample(
                    observedAtMs = nowMs,
                    slotIndex = binding.slotIndex,
                    subId = binding.subId,
                    operatorName = op,
                    plmn = plmn,
                    rat = RfRat.NR,
                    arfcn = arfcn,
                    carrierAggregation = binding.lastCa,
                    rsrpDbm = sig?.ssRsrp,
                    rsrqDb = sig?.ssRsrq,
                    sinrDb = sig?.ssSinr,
                    cellTimestampMs = primary.timestampMillis,
                    inService = binding.lastInService,
                    source = RfSampleSource.ACTIVE_CELL_INFO
                )
            }
            is CellInfoLte -> {
                val id = primary.cellIdentity as? CellIdentityLte
                val sig = primary.cellSignalStrength as? CellSignalStrengthLte
                val arfcn = cleanArfcn(id?.earfcn) ?: binding.lastArfcn
                if (arfcn != null) binding.lastArfcn = arfcn
                val plmn = buildPlmn(id?.mccString, id?.mncString).ifBlank { binding.plmn }
                val op = id?.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() } ?: binding.operatorName
                RfCellSample(
                    observedAtMs = nowMs,
                    slotIndex = binding.slotIndex,
                    subId = binding.subId,
                    operatorName = op,
                    plmn = plmn,
                    rat = RfRat.LTE,
                    arfcn = arfcn,
                    carrierAggregation = binding.lastCa,
                    rsrpDbm = sig?.rsrp,
                    rsrqDb = sig?.rsrq,
                    sinrDb = sig?.rssnr,
                    cellTimestampMs = primary.timestampMillis,
                    inService = binding.lastInService,
                    source = RfSampleSource.ACTIVE_CELL_INFO
                )
            }
            else -> RfCellSample(
                observedAtMs = nowMs,
                slotIndex = binding.slotIndex,
                subId = binding.subId,
                operatorName = binding.operatorName,
                plmn = binding.plmn,
                rat = RfRat.OTHER,
                arfcn = binding.lastArfcn,
                carrierAggregation = binding.lastCa,
                cellTimestampMs = primary.timestampMillis,
                inService = binding.lastInService,
                source = RfSampleSource.ACTIVE_CELL_INFO
            )
        }
        onSample(sample)
    }

    private fun handleSignalStrength(binding: SlotBinding, ss: SignalStrength) {
        if (!running.get()) return
        val nowMs = System.currentTimeMillis()
        val nr = ss.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
        val lte = ss.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
        val sample = when {
            nr != null -> RfCellSample(
                observedAtMs = nowMs,
                slotIndex = binding.slotIndex,
                subId = binding.subId,
                operatorName = binding.operatorName,
                plmn = binding.plmn,
                rat = RfRat.NR,
                arfcn = binding.lastArfcn,
                carrierAggregation = binding.lastCa,
                rsrpDbm = nr.ssRsrp,
                rsrqDb = nr.ssRsrq,
                sinrDb = nr.ssSinr,
                inService = binding.lastInService,
                source = RfSampleSource.SIGNAL_CALLBACK
            )
            lte != null -> RfCellSample(
                observedAtMs = nowMs,
                slotIndex = binding.slotIndex,
                subId = binding.subId,
                operatorName = binding.operatorName,
                plmn = binding.plmn,
                rat = RfRat.LTE,
                arfcn = binding.lastArfcn,
                carrierAggregation = binding.lastCa,
                rsrpDbm = lte.rsrp,
                rsrqDb = lte.rsrq,
                sinrDb = lte.rssnr,
                inService = binding.lastInService,
                source = RfSampleSource.SIGNAL_CALLBACK
            )
            !binding.lastInService -> RfCellSample(
                observedAtMs = nowMs,
                slotIndex = binding.slotIndex,
                subId = binding.subId,
                operatorName = binding.operatorName,
                plmn = binding.plmn,
                rat = RfRat.NO_SIGNAL,
                inService = false,
                source = RfSampleSource.SIGNAL_CALLBACK
            )
            else -> return
        }
        // 在后台线程过滤 1.5 秒内指标完全一致的突发重复信号回调，避免频繁唤醒主线程与 UI 重组。
        // Coalesce identical bursty SignalStrength callbacks within 1.5s off the main thread.
        val fingerprint = SignalFingerprint(sample.rat, sample.rsrpDbm, sample.rsrqDb, sample.sinrDb, sample.inService)
        if (!anomalyEscalated &&
            binding.lastSignalFingerprint == fingerprint &&
            (nowMs - binding.lastSignalAtMs) < SIGNAL_DEDUP_WINDOW_MS
        ) {
            return
        }
        binding.lastSignalFingerprint = fingerprint
        binding.lastSignalAtMs = nowMs
        onSample(sample)
    }

    private fun handleServiceState(binding: SlotBinding, serviceState: ServiceState) {
        if (!running.get()) return
        val nowMs = System.currentTimeMillis()
        val inService = serviceState.state == ServiceState.STATE_IN_SERVICE
        val arfcn = cleanArfcn(serviceState.channelNumber)
        if (arfcn != null) binding.lastArfcn = arfcn
        binding.lastCa = runCatching { serviceState.cellBandwidths.size > 1 }.getOrDefault(false)
        binding.lastInService = inService

        if (!inService) {
            onSample(
                RfCellSample(
                    observedAtMs = nowMs,
                    slotIndex = binding.slotIndex,
                    subId = binding.subId,
                    operatorName = binding.operatorName,
                    plmn = binding.plmn,
                    rat = RfRat.NO_SIGNAL,
                    arfcn = null,
                    carrierAggregation = false,
                    inService = false,
                    source = RfSampleSource.SERVICE_STATE
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephonyListeners(binding: SlotBinding) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.CellInfoListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    handleSignalStrength(binding, signalStrength)
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    handleServiceState(binding, serviceState)
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    if (!running.get() || cellInfo.isEmpty()) return
                    handleCellInfoList(binding, cellInfo)
                }
            }
            runCatching {
                binding.manager.registerTelephonyCallback(executor, cb)
                binding.callback31 = cb
            }.onFailure { err ->
                Log.w("WieslerRF", "registerTelephonyCallback failed slot=${binding.slotIndex}: ${err.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener(executor) {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    if (signalStrength != null) handleSignalStrength(binding, signalStrength)
                }

                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(serviceState: ServiceState?) {
                    if (serviceState != null) handleServiceState(binding, serviceState)
                }
            }
            @Suppress("DEPRECATION")
            runCatching {
                binding.manager.listen(
                    listener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE
                )
                binding.legacyListener = listener
            }.onFailure { err ->
                Log.w("WieslerRF", "PhoneStateListener.listen failed slot=${binding.slotIndex}: ${err.message}")
            }
        }
    }

    private fun unregisterTelephonyListeners(binding: SlotBinding) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (binding.callback31 as? TelephonyCallback)?.let { cb ->
                runCatching { binding.manager.unregisterTelephonyCallback(cb) }
            }
            binding.callback31 = null
        } else {
            @Suppress("DEPRECATION")
            binding.legacyListener?.let { listener ->
                runCatching { binding.manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
            binding.legacyListener = null
        }
    }

    private fun registerCellularLinkMonitor() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (lastReportedValidated == validated) return
                lastReportedValidated = validated
                onCellularLinkState(validated, if (validated) null else System.currentTimeMillis())
            }

            override fun onLost(network: Network) {
                lastReportedValidated = false
                onCellularLinkState(false, System.currentTimeMillis())
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, cb)
            cellularNetworkCallback = cb
        }.onFailure { err ->
            Log.w("WieslerRF", "registerNetworkCallback(CELLULAR) failed: ${err.message}")
        }
    }

    private fun unregisterCellularLinkMonitor() {
        val cb = cellularNetworkCallback ?: return
        cellularNetworkCallback = null
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        }
    }

    private fun buildPlmn(mcc: String?, mnc: String?): String =
        if (!mcc.isNullOrBlank() && !mnc.isNullOrBlank()) "$mcc$mnc" else ""
}
