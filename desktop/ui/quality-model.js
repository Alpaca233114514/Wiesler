(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.QualityModel = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, () => {
  'use strict';

  const PERFORMANCE_MODES = Object.freeze({
    eco: Object.freeze({
      code: 'eco',
      label: '极致省电',
      summary: '3s 采样 · 关闭图谱脉冲动效 · 最低 CPU 与后台占用',
      activeTickMs: 3000,
      systemTickMs: 6000,
      icmpIntervalMs: 3000,
      rfSteadyPollMs: 12000,
      rfEscalatedPollMs: 4000,
      allowPulseAnimation: false
    }),
    balanced: Object.freeze({
      code: 'balanced',
      label: '均衡模式',
      summary: '1s 活跃采样 · RF 异常自动提速 · 兼顾响应与占用（推荐）',
      activeTickMs: 1000,
      systemTickMs: 3000,
      icmpIntervalMs: 1000,
      rfSteadyPollMs: 6000,
      rfEscalatedPollMs: 3000,
      allowPulseAnimation: true
    }),
    high: Object.freeze({
      code: 'high',
      label: '高频排障',
      summary: '500ms 高频采样 · 3s Wi-Fi/ADB 轮询 · 适合短时精细取证',
      activeTickMs: 500,
      systemTickMs: 1500,
      icmpIntervalMs: 500,
      rfSteadyPollMs: 3000,
      rfEscalatedPollMs: 2000,
      allowPulseAnimation: true
    })
  });

  const THEME_MODES = Object.freeze({
    system: Object.freeze({ code: 'system', label: '跟随系统' }),
    light: Object.freeze({ code: 'light', label: '浅色' }),
    dark: Object.freeze({ code: 'dark', label: '深色' })
  });

  const QUICK_PING_TARGETS = Object.freeze([
    Object.freeze({ ip: '223.5.5.5', label: '阿里 DNS' }),
    Object.freeze({ ip: '1.1.1.1', label: 'Cloudflare' }),
    Object.freeze({ ip: '8.8.8.8', label: 'Google' })
  ]);

  const DIMENSION_META = Object.freeze({
    access_signal: Object.freeze({ id: 'access_signal', label: '接入与信号', weight: 0.30 }),
    latency_stability: Object.freeze({ id: 'latency_stability', label: '延迟与稳定', weight: 0.35 }),
    reachability_dns: Object.freeze({ id: 'reachability_dns', label: '解析与连通', weight: 0.20 }),
    proxy_health: Object.freeze({ id: 'proxy_health', label: '代理与链路', weight: 0.15 })
  });

  const GRADES = Object.freeze({
    EXCELLENT: Object.freeze({ id: 'EXCELLENT', badge: 'S', label: '极佳', tone: 'ok' }),
    GOOD: Object.freeze({ id: 'GOOD', badge: 'A', label: '良好', tone: 'ok' }),
    FAIR: Object.freeze({ id: 'FAIR', badge: 'B', label: '一般', tone: 'warn' }),
    POOR: Object.freeze({ id: 'POOR', badge: 'C', label: '较差', tone: 'warn' }),
    CRITICAL: Object.freeze({ id: 'CRITICAL', badge: 'D', label: '严重异常', tone: 'danger' }),
    OFFLINE: Object.freeze({ id: 'OFFLINE', badge: '—', label: '已离线', tone: 'danger' })
  });

  function resolvePerformanceMode(code) {
    return PERFORMANCE_MODES[code] || PERFORMANCE_MODES.balanced;
  }

  function resolveThemeMode(code) {
    return THEME_MODES[code] || THEME_MODES.system;
  }

  function clamp(v, min, max) {
    return Math.min(max, Math.max(min, v));
  }

  function computeJitterMs(pings) {
    if (!Array.isArray(pings)) return null;
    const rtts = pings
      .filter(p => p && p.outcome === 'reply' && Number.isFinite(p.rtt_ms) && p.rtt_ms >= 0)
      .map(p => p.rtt_ms);
    if (rtts.length < 2) return null;
    let diffSum = 0;
    for (let i = 1; i < rtts.length; i++) {
      diffSum += Math.abs(rtts[i] - rtts[i - 1]);
    }
    return diffSum / (rtts.length - 1);
  }

  function scoreWifiRssi(rssiDbm) {
    if (rssiDbm >= -55) return 98;
    if (rssiDbm >= -65) return 98 - (-55 - rssiDbm) * 1.0;
    if (rssiDbm >= -75) return 88 - (-65 - rssiDbm) * 1.6;
    if (rssiDbm >= -85) return 72 - (-75 - rssiDbm) * 2.4;
    return Math.max(12, 48 - (-85 - rssiDbm) * 1.8);
  }

  function wifiRssiGradeLabel(rssiDbm) {
    if (rssiDbm >= -55) return '信号满格';
    if (rssiDbm >= -67) return '信号良好';
    if (rssiDbm >= -76) return '信号一般';
    return '信号偏弱';
  }

  function scoreCellularSlot(slot) {
    if (!slot || !slot.in_service || slot.rat === 'NO_SIGNAL') return 5;
    let rsrpScore = null;
    if (Number.isFinite(slot.rsrp_dbm)) {
      const rsrp = slot.rsrp_dbm;
      if (rsrp >= -85) rsrpScore = 98;
      else if (rsrp >= -100) rsrpScore = 98 - (-85 - rsrp) * 1.1;
      else if (rsrp >= -115) rsrpScore = 81.5 - (-100 - rsrp) * 2.3;
      else rsrpScore = Math.max(10, 47 - (-115 - rsrp) * 1.5);
    }
    let sinrScore = null;
    if (Number.isFinite(slot.sinr_db)) {
      const sinr = slot.sinr_db;
      if (sinr >= 15) sinrScore = 98;
      else if (sinr >= 5) sinrScore = 78 + (sinr - 5) * 2.0;
      else if (sinr >= 0) sinrScore = 55 + sinr * 4.6;
      else sinrScore = Math.max(8, 55 + sinr * 3.5);
    }
    if (rsrpScore != null && sinrScore != null) return rsrpScore * 0.45 + sinrScore * 0.55;
    if (sinrScore != null) return sinrScore;
    if (rsrpScore != null) return rsrpScore;
    return 70;
  }

  function formatMbps(mbps) {
    if (!Number.isFinite(mbps) || mbps <= 0) return null;
    return mbps >= 1000 ? `${(mbps / 1000).toFixed(1)} Gbps` : `${Math.round(mbps)} Mbps`;
  }

  function scoreAccessAndSignal(input) {
    const meta = DIMENSION_META.access_signal;
    const link = input.link || {};
    if (link.connected === false) {
      return {
        ...meta,
        score: 0,
        available: true,
        statusText: '未连接',
        primaryMetric: '无活跃接口',
        detail: '系统未检测到可用网络连接。'
      };
    }

    const rf = input.rfReport || null;
    const validatedKnown = typeof link.validated === 'boolean';
    const validatedNote = validatedKnown ? (link.validated ? '联网校验通过' : '联网校验未通过') : '本机接口在线';
    const slots = Array.isArray(rf?.slots) ? rf.slots : [];

    // 优先使用 ADB 手机双卡基带与本机 Wi-Fi 实测信号；未开启射频监测时回退至本机接口状态。
    // Prefer measured ADB dual-SIM / local Wi-Fi RF telemetry; fall back to local host link state.
    if (slots.length > 0) {
      const rfScore = Number.isFinite(rf.score) ? rf.score : 0;
      const rfClean = clamp(100 - rfScore, 0, 100);
      const bestSlot = slots.reduce((best, s) => ((s.fresh_frames || 0) > (best.fresh_frames || 0) ? s : best), slots[0]);
      const radioLevel = scoreCellularSlot(bestSlot);
      let score = rfClean * 0.65 + radioLevel * 0.35;
      if (rf.severity === 'severe') score = Math.min(score, 22);
      if (rf.wifi?.bottleneck) score = Math.min(score, 48);
      if (link.validated === false) score = Math.min(score, 40);
      const finalScore = clamp(Math.round(score), 0, 100);
      const shortBand = String(bestSlot.band || bestSlot.rat || '蜂窝').split(' (')[0];
      const rsrpText = Number.isFinite(bestSlot.rsrp_dbm) ? `${bestSlot.rsrp_dbm} dBm` : '';
      const sinrText = Number.isFinite(bestSlot.sinr_db) ? `SINR ${bestSlot.sinr_db > 0 ? '+' : ''}${bestSlot.sinr_db} dB` : '';
      const metric = [shortBand, rsrpText].filter(Boolean).join(' · ') || shortBand;
      const wifiNote = rf.wifi?.available && Number.isFinite(rf.wifi.rssi_dbm) ? ` · Wi-Fi ${rf.wifi.rssi_dbm} dBm` : '';
      return {
        ...meta,
        score: finalScore,
        available: true,
        statusText: rf.severity_label || '射频监测中',
        primaryMetric: metric,
        detail: `净度 ${Math.round(rfClean)}/100${sinrText ? ` · ${sinrText}` : ''}${wifiNote} · ${validatedNote}`
      };
    }

    if (rf?.wifi?.available) {
      const w = rf.wifi;
      const rssi = Number.isFinite(w.rssi_dbm) && w.rssi_dbm >= -120 && w.rssi_dbm <= -20 ? w.rssi_dbm : null;
      let score = rssi != null
        ? scoreWifiRssi(rssi)
        : Number.isFinite(w.signal_percent)
          ? clamp(w.signal_percent, 15, 98)
          : (link.validated === false ? 36 : 82);
      if (Number.isFinite(w.rx_mbps)) {
        if (w.rx_mbps >= 150) score += 3;
        else if (w.rx_mbps < 2) score -= 24;
        else if (w.rx_mbps < 10) score -= 12;
      }
      if (w.bottleneck) score = Math.min(score, 48);
      if (link.validated === false) score = Math.min(score, 40);
      const finalScore = clamp(Math.round(score), 5, 100);
      const bw = formatMbps(w.rx_mbps);
      const metric = [rssi != null ? `${rssi} dBm` : (w.signal_percent != null ? `${w.signal_percent}%` : null), bw ? `↓${bw}` : null]
        .filter(Boolean)
        .join(' · ') || 'Wi-Fi';
      return {
        ...meta,
        score: finalScore,
        available: true,
        statusText: w.bottleneck ? 'Wi-Fi 瓶颈' : (rssi != null ? wifiRssiGradeLabel(rssi) : validatedNote),
        primaryMetric: metric,
        detail: `${w.ssid || 'Wi-Fi'}${w.channel ? ` · CH${w.channel}` : ''} · ${validatedNote}`
      };
    }

    const baseScore = link.validated === false ? 40 : (link.validated === true ? 90 : 80);
    const label = link.transportLabel || '有线 / 系统网络';
    return {
      ...meta,
      score: baseScore,
      available: true,
      statusText: validatedNote,
      primaryMetric: label,
      detail: '开启射频监测可采集 Wi-Fi RSSI / ADB 双卡信号'
    };
  }

  function scoreLatencyAndStability(input, jitterMs) {
    const meta = DIMENSION_META.latency_stability;
    const sent = Number(input.pingSent) || 0;
    const received = Number(input.pingReceived) || 0;
    const lossPct = Number.isFinite(input.lossPercent) ? input.lossPercent : null;
    const avgRtt = Number.isFinite(input.averageRttMs) ? input.averageRttMs : null;
    const target = input.pingTarget || '1.1.1.1';

    if (sent <= 0 || lossPct == null) {
      return {
        ...meta,
        score: null,
        available: false,
        statusText: input.pingRunning ? '探测中' : '待体检',
        primaryMetric: '—',
        detail: `目标 ${target} · 点击一键体检或开启持续监测采样延迟与丢包`
      };
    }

    if (lossPct >= 99.9 || avgRtt == null) {
      return {
        ...meta,
        score: 0,
        available: true,
        statusText: '全部丢包',
        primaryMetric: `丢包 ${lossPct.toFixed(0)}%`,
        detail: `目标 ${target} · 发送 ${sent} 收到 ${received}，ICMP 无回包`
      };
    }

    let rttScore;
    if (avgRtt <= 25) rttScore = 100;
    else if (avgRtt <= 60) rttScore = 100 - (avgRtt - 25) * (12 / 35);
    else if (avgRtt <= 120) rttScore = 88 - (avgRtt - 60) * (18 / 60);
    else if (avgRtt <= 220) rttScore = 70 - (avgRtt - 120) * (25 / 100);
    else if (avgRtt <= 400) rttScore = 45 - (avgRtt - 220) * (25 / 180);
    else rttScore = Math.max(5, 20 - (avgRtt - 400) * 0.04);

    let lossPenalty;
    if (lossPct <= 0) lossPenalty = 0;
    else if (lossPct <= 2) lossPenalty = lossPct * 8;
    else if (lossPct <= 10) lossPenalty = 16 + (lossPct - 2) * 4.5;
    else lossPenalty = 52 + (lossPct - 10) * 1.6;

    let jitterPenalty;
    if (jitterMs == null || jitterMs <= 5) jitterPenalty = 0;
    else if (jitterMs <= 25) jitterPenalty = (jitterMs - 5) * 0.6;
    else if (jitterMs <= 80) jitterPenalty = 12 + (jitterMs - 25) * 0.35;
    else jitterPenalty = Math.min(36, 31.25 + (jitterMs - 80) * 0.1);

    const score = clamp(Math.round(rttScore - lossPenalty - jitterPenalty), 0, 100);
    const jitterText = jitterMs != null ? `抖动 ${jitterMs.toFixed(1)}ms` : '抖动待补齐';
    const statusText = lossPct >= 10 ? '高丢包'
      : avgRtt >= 220 ? '高延迟'
        : (jitterMs || 0) >= 35 ? '抖动偏大'
          : score >= 85 ? '低延迟平稳'
            : '轻度波动';

    return {
      ...meta,
      score,
      available: true,
      statusText,
      primaryMetric: `${Math.round(avgRtt)} ms · 丢包 ${lossPct.toFixed(1)}%`,
      detail: `${target} · ${jitterText} · 收发 ${received}/${sent}`
    };
  }

  function scoreReachabilityAndDns(input) {
    const meta = DIMENSION_META.reachability_dns;
    const subScores = [];
    const parts = [];

    const dns = input.dnsInspection || null;
    if (dns) {
      const queries = Array.isArray(dns.queries) ? dns.queries : [];
      const okQueries = queries.filter(q => !q.error && Array.isArray(q.addresses) && q.addresses.length > 0);
      if (dns.error || (queries.length > 0 && okQueries.length === 0)) {
        parts.push('DNS 失败');
        subScores.push(15);
      } else if (okQueries.length > 0) {
        const ms = okQueries.reduce((sum, q) => sum + (Number(q.elapsedMs ?? q.elapsed_ms) || 0), 0) / okQueries.length;
        parts.push(`DNS ${Math.round(ms)}ms`);
        let base;
        if (ms <= 30) base = 98;
        else if (ms <= 80) base = 98 - (ms - 30) * (12 / 50);
        else if (ms <= 200) base = 86 - (ms - 80) * (21 / 120);
        else if (ms <= 500) base = 65 - (ms - 200) * (27 / 300);
        else base = 22;
        subScores.push(Math.min(100, base));
      }
    }

    const https = input.httpsProbe || null;
    if (https && (https.status_code != null || https.statusCode != null || https.error)) {
      const code = https.status_code ?? https.statusCode ?? null;
      const ms = Number(https.latency_ms ?? https.latencyMs ?? 0);
      if (https.error) {
        parts.push(`HTTPS ${https.error}`);
        subScores.push(18);
      } else if (https.ok) {
        const label = ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${Math.round(ms)}ms`;
        parts.push(`204 ${label}`);
        let score;
        if (ms <= 150) score = 98;
        else if (ms <= 350) score = 98 - (ms - 150) * (14 / 200);
        else if (ms <= 800) score = 84 - (ms - 350) * (26 / 450);
        else if (ms <= 1600) score = 58 - (ms - 800) * (24 / 800);
        else score = 24;
        subScores.push(score);
      } else {
        parts.push(`HTTP ${code}`);
        subScores.push(55);
      }
    }

    if (input.systemEgress?.ip) {
      subScores.push(input.systemEgress.location_error ? 86 : 95);
      parts.push(`出口 ${input.systemEgress.ip}`);
    } else if (input.systemEgressError) {
      subScores.push(20);
      parts.push('出口不可达');
    }

    if (!subScores.length) {
      return {
        ...meta,
        score: null,
        available: false,
        statusText: '待体检',
        primaryMetric: '—',
        detail: '点击一键体检测试系统 DNS、HTTPS 204 与公网出口'
      };
    }

    const score = clamp(Math.round(subScores.reduce((a, b) => a + b, 0) / subScores.length), 0, 100);
    const statusText = score >= 85 ? '解析通畅' : score >= 60 ? '可用但偏慢' : '连通受阻';
    const firstResolver = dns?.resolvers?.[0]?.server;
    const prefix = firstResolver ? `DNS ${firstResolver}` : '系统解析';
    return {
      ...meta,
      score,
      available: true,
      statusText,
      primaryMetric: parts.slice(0, 2).join(' · '),
      detail: `${prefix} · ${parts.join(' / ')}`
    };
  }

  function scoreProxyHealth(input) {
    const meta = DIMENSION_META.proxy_health;
    const hasProxy = Boolean(input.controllerEnabled || input.proxyEgress || input.proxyEgressError);
    if (!hasProxy) {
      return {
        ...meta,
        score: null,
        available: false,
        statusText: '未连接',
        primaryMetric: '直连模式',
        detail: '未连接 Mihomo 控制器，该项不计扣分；连接后自动纳入综合评分'
      };
    }

    let score = input.controllerHealthy ? 88 : 28;
    if (input.proxyEgress?.ip) score += 10;
    else if (input.proxyEgressError) score -= 32;

    const logs = Array.isArray(input.recentRequestLogs) ? input.recentRequestLogs.slice(0, 20) : [];
    if (logs.length > 0) {
      const failRate = logs.filter(l => l.failed || l.result === '失败' || Boolean(l.error)).length / logs.length;
      score -= failRate * 35;
    }

    const finalScore = clamp(Math.round(score), 0, 100);
    const splitText = input.systemEgress?.ip && input.proxyEgress?.ip && input.systemEgress.ip === input.proxyEgress.ip
      ? '同出口'
      : input.systemEgress?.ip && input.proxyEgress?.ip
        ? '已分流'
        : input.proxyEgress?.ip
          ? '出口可达'
          : input.proxyEgressError
            ? '代理出口异常'
            : '未测出口';
    const flowText = Number.isFinite(input.activeFlowCount)
      ? `${input.activeFlowCount} 连接（代理 ${input.proxyFlowCount || 0}）`
      : '等待快照';
    const place = input.proxyEgress?.location
      ? [input.proxyEgress.location.country, input.proxyEgress.location.city].filter(Boolean).join(' · ')
      : '';

    return {
      ...meta,
      score: finalScore,
      available: true,
      statusText: input.controllerHealthy ? splitText : '控制器异常',
      primaryMetric: input.controllerHealthy ? flowText : '连接断开',
      detail: input.proxyEgress?.ip
        ? `代理出口 ${input.proxyEgress.ip}${place ? ` · ${place}` : ''}`
        : (input.proxyEgressError || (input.controllerHealthy ? 'Mihomo 控制器心跳正常' : '无法读取本机 Mihomo 快照'))
    };
  }

  function gradeFor(score, connected) {
    if (!connected) return GRADES.OFFLINE;
    if (score >= 90) return GRADES.EXCELLENT;
    if (score >= 75) return GRADES.GOOD;
    if (score >= 55) return GRADES.FAIR;
    if (score >= 30) return GRADES.POOR;
    return GRADES.CRITICAL;
  }

  function buildScenarios(input, overall, jitterMs) {
    const link = input.link || {};
    if (link.connected === false) {
      return [
        { id: 'voice', title: '语音 / 会议', status: 'degraded', statusLabel: '受限', detail: '离线不可用' },
        { id: 'game', title: '低延迟游戏', status: 'degraded', statusLabel: '受限', detail: '离线不可用' },
        { id: 'stream', title: '高清流媒体', status: 'degraded', statusLabel: '受限', detail: '离线不可用' },
        { id: 'proxy', title: '代理 / 跨境', status: 'degraded', statusLabel: '受限', detail: '离线不可用' }
      ];
    }

    const rtt = Number.isFinite(input.averageRttMs) ? input.averageRttMs : null;
    const loss = Number.isFinite(input.lossPercent) ? input.lossPercent : null;
    const failedValidation = link.validated === false;
    const labels = { optimal: '流畅', usable: '可用', degraded: '受限', unknown: '待测' };
    const mk = (id, title, status, detail) => ({ id, title, status, statusLabel: labels[status], detail });

    let voice;
    if (failedValidation) voice = ['degraded', '联网校验未通过'];
    else if (rtt == null || loss == null) voice = ['unknown', '待测延迟与丢包'];
    else if (loss < 1.5 && rtt < 120 && (jitterMs || 0) < 30) voice = ['optimal', `${Math.round(rtt)}ms 稳定`];
    else if (loss < 4 && rtt < 220) voice = ['usable', '偶发波动'];
    else voice = ['degraded', '易卡顿或断音'];

    let game;
    if (failedValidation) game = ['degraded', '联网校验未通过'];
    else if (rtt == null || loss == null) game = ['unknown', '待测延迟与抖动'];
    else if (loss < 1 && rtt < 65 && (jitterMs || 0) < 18) game = ['optimal', `${Math.round(rtt)}ms 低抖动`];
    else if (loss < 2.5 && rtt < 120) game = ['usable', '中度延迟'];
    else game = ['degraded', '延迟或丢包偏高'];

    const rxMbps = input.rfReport?.wifi?.rx_mbps ?? null;
    let stream;
    if (failedValidation) stream = ['degraded', '联网校验未通过'];
    else if ((loss || 0) >= 8 || overall < 50) stream = ['degraded', '易缓冲'];
    else if ((rxMbps == null || rxMbps >= 25) && overall >= 75) stream = ['optimal', '带宽与链路充裕'];
    else if (overall >= 65) stream = ['usable', '可流畅播放'];
    else stream = ['degraded', '带宽或链路受限'];

    const https = input.httpsProbe;
    let proxy;
    if (!input.controllerEnabled && !https) proxy = ['unknown', '未接入控制器'];
    else if (input.controllerEnabled && !input.controllerHealthy) proxy = ['degraded', '控制器断开'];
    else if (input.proxyEgressError) proxy = ['degraded', '代理出口失败'];
    else if (input.proxyEgress?.ip && (!https || https.ok)) proxy = ['optimal', '代理链路正常'];
    else if (https?.ok) proxy = ['optimal', '204 连通正常'];
    else if (https?.error) proxy = ['degraded', '204 超时或阻断'];
    else proxy = ['usable', '控制器已连'];

    return [
      mk('voice', '语音 / 会议', voice[0], voice[1]),
      mk('game', '低延迟游戏', game[0], game[1]),
      mk('stream', '高清流媒体', stream[0], stream[1]),
      mk('proxy', '代理 / 跨境', proxy[0], proxy[1])
    ];
  }

  function buildDiagnosisText(input, overall, grade, jitterMs, dimensions, measuredCount, totalApplicable) {
    const attr = input.rfReport?.attribution;
    if (attr && ['CELLULAR_RF_POLLUTION', 'WIFI_HOTSPOT_BOTTLENECK', 'COMPOUND_FAULT'].includes(attr.cause)) {
      return {
        headline: attr.title,
        summary: attr.detail,
        advice: attr.suggestion || '建议排查射频或无线热点瓶颈。'
      };
    }

    const link = input.link || {};
    const transport = link.transportLabel || (input.rfReport?.wifi?.available ? 'Wi-Fi' : '本机网络');
    const envPrefix = transport.endsWith('网络') ? transport : `${transport} 网络`;
    let headline;
    if (link.validated === false) headline = `已连接 ${transport}，但系统联网校验未通过`;
    else if ((input.lossPercent || 0) >= 15) headline = `链路存在明显丢包（${input.lossPercent.toFixed(1)}%）`;
    else if (grade.id === 'EXCELLENT') headline = `${envPrefix}环境极佳`;
    else if (grade.id === 'GOOD') headline = `${envPrefix}环境良好`;
    else if (grade.id === 'FAIR') headline = `${envPrefix}存在轻度瓶颈`;
    else headline = `${envPrefix}质量较差`;

    const summaryParts = [];
    if (Number.isFinite(input.averageRttMs)) summaryParts.push(`RTT ${Math.round(input.averageRttMs)}ms`);
    if (Number.isFinite(input.lossPercent)) summaryParts.push(`丢包 ${input.lossPercent.toFixed(1)}%`);
    if (jitterMs != null) summaryParts.push(`抖动 ${jitterMs.toFixed(1)}ms`);
    if (input.httpsProbe?.ok && Number.isFinite(input.httpsProbe.latency_ms)) summaryParts.push(`204 ${Math.round(input.httpsProbe.latency_ms)}ms`);
    if (input.rfReport?.wifi?.available && Number.isFinite(input.rfReport.wifi.rssi_dbm)) summaryParts.push(`Wi-Fi ${input.rfReport.wifi.rssi_dbm}dBm`);
    const summary = summaryParts.join(' · ') || `已测 ${measuredCount}/${totalApplicable} 维 · 点击一键体检补齐延迟与连通性`;

    const weakest = dimensions
      .filter(d => d.available && d.score != null)
      .reduce((min, d) => (!min || d.score < min.score ? d : min), null);

    let advice;
    if (link.validated === false) advice = '请检查网关认证、系统代理配置或出口隧道状态。';
    else if (measuredCount < totalApplicable) advice = `当前已完成 ${measuredCount}/${totalApplicable} 项采样，点击「一键体检」补齐延迟、DNS 与出口维度。`;
    else if (weakest && weakest.score < 60) advice = `主要短板在「${weakest.label}」（${weakest.score} 分）：${weakest.detail}。`;
    else if (overall >= 85) advice = '各项已测指标均衡平稳，可开启持续监测追踪长时抖动。';
    else advice = `整体可用，建议关注 ${weakest ? weakest.label : '链路延迟'} 的连续波动。`;

    return { headline, summary, advice };
  }

  function evaluate(input = {}, history = []) {
    const nowMs = Number.isFinite(input.nowMs) ? input.nowMs : Date.now();
    const jitterMs = computeJitterMs(input.recentPings);
    const accessDim = scoreAccessAndSignal(input);
    const latencyDim = scoreLatencyAndStability(input, jitterMs);
    const reachDim = scoreReachabilityAndDns(input);
    const proxyDim = scoreProxyHealth(input);

    const dimensions = [accessDim, latencyDim, reachDim, proxyDim];
    const proxyApplicable = Boolean(input.controllerEnabled || input.proxyEgress || input.proxyEgressError);
    const totalApplicable = proxyApplicable ? 4 : 3;
    const measured = dimensions.filter(d => d.available && d.score != null);

    if (input.link?.connected === false) {
      return {
        evaluatedAtMs: nowMs,
        overallScore: 0,
        grade: GRADES.OFFLINE,
        headline: '设备当前离线',
        summary: '系统未检测到活跃 Wi-Fi 或有线网络连接。',
        advice: '请检查网线、Wi-Fi 开关或系统网络适配器状态。',
        measuredDimensions: measured.length,
        totalApplicableDimensions: totalApplicable,
        jitterMs,
        dimensions,
        scenarios: buildScenarios(input, 0, jitterMs),
        history
      };
    }

    // 按实际已测维度动态归一化权重，绝不伪造未探测维度的分数。
    // Dynamically normalize weights strictly over measured dimensions so unprobed metrics never fabricate scores.
    const weightSum = Math.max(1e-6, measured.reduce((sum, d) => sum + d.weight, 0));
    let weighted = measured.reduce((sum, d) => sum + d.score * d.weight, 0) / weightSum;

    // 关键短板硬封顶：防止严重丢包、射频污染或无线瓶颈被平均值掩盖。
    // Hard bottleneck caps prevent severe loss, RF pollution, or Wi-Fi bottlenecks from being hidden by averages.
    const lossPct = Number.isFinite(input.lossPercent) ? input.lossPercent : null;
    if (lossPct != null && lossPct >= 30) weighted = Math.min(weighted, 39);
    else if (lossPct != null && lossPct >= 15) weighted = Math.min(weighted, 54);
    if (input.rfReport?.severity === 'severe') weighted = Math.min(weighted, 42);
    if (input.rfReport?.wifi?.bottleneck) weighted = Math.min(weighted, 55);
    if (input.link?.validated === false) weighted = Math.min(weighted, 45);

    const overall = clamp(Math.round(weighted), 1, 100);
    const grade = gradeFor(overall, true);
    const diag = buildDiagnosisText(input, overall, grade, jitterMs, dimensions, measured.length, totalApplicable);

    return {
      evaluatedAtMs: nowMs,
      overallScore: overall,
      grade,
      headline: diag.headline,
      summary: diag.summary,
      advice: diag.advice,
      measuredDimensions: measured.length,
      totalApplicableDimensions: totalApplicable,
      jitterMs,
      dimensions,
      scenarios: buildScenarios(input, overall, jitterMs),
      history
    };
  }

  function createHistoryBuffer(maxPoints = 60) {
    const points = [];
    return {
      record(snapshot, rttMs = null, lossPercent = null) {
        if (!snapshot || !Number.isFinite(snapshot.overallScore)) return;
        const last = points[points.length - 1];
        if (last && snapshot.evaluatedAtMs - last.observedAtMs < 900) return;
        points.push({
          observedAtMs: snapshot.evaluatedAtMs,
          score: snapshot.overallScore,
          rttMs: Number.isFinite(rttMs) ? rttMs : null,
          lossPercent: Number.isFinite(lossPercent) ? lossPercent : null
        });
        while (points.length > maxPoints) points.shift();
      },
      clear() {
        points.length = 0;
      },
      snapshot() {
        return points.slice();
      }
    };
  }

  return {
    PERFORMANCE_MODES,
    THEME_MODES,
    QUICK_PING_TARGETS,
    DIMENSION_META,
    GRADES,
    resolvePerformanceMode,
    resolveThemeMode,
    computeJitterMs,
    evaluate,
    createHistoryBuffer
  };
});
