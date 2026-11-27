(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const invoke = window.__TAURI__?.core?.invoke;
  const requestLogs = [];
  const changeListeners = new Set();
  function notifyChange() {
    for (const fn of changeListeners) {
      try { fn(); } catch (_) {}
    }
  }
  function appendRequestLog(code, error) {
    const status = Number.isInteger(code) && code >= 100 && code <= 599 ? code : null;
    const failed = Boolean(error) || status >= 400;
    const result = failed ? '失败' : status >= 200 && status < 300 ? '成功' : status >= 300 ? '重定向' : '未知';
    requestLogs.unshift({time:new Date().toLocaleTimeString(), status, result, failed, error});
    requestLogs.length = Math.min(requestLogs.length, 100);
    $('request-log-rows').replaceChildren();
    for (const entry of requestLogs) {
      const tr = document.createElement('tr');
      [entry.time, 'Mihomo /connections', entry.status ? `HTTP ${entry.status}` : '未取得状态码', entry.result, entry.error || '—'].forEach((value, index) => {
        const td = document.createElement('td'); td.textContent = value;
        if (index === 2 || index === 3) td.className = entry.result === '失败' ? 'http-error' : entry.result === '成功' ? 'green' : 'amber';
        tr.append(td);
      });
      $('request-log-rows').append(tr);
    }
    notifyChange();
  }
  const DB_DIRS_KEY = 'wiesler.diagnostics.dirs';
  let dbGeneration = 0, loadedDatabase = '', dbDiscoveredOnce = false, dbDiscoverBusy = false, discoveredDatabases = [];
  function readSavedDirs() {
    try {
      const raw = window.localStorage?.getItem(DB_DIRS_KEY);
      const list = raw ? JSON.parse(raw) : [];
      return Array.isArray(list) ? list.filter(item => typeof item === 'string' && item.trim()) : [];
    } catch (_) { return []; }
  }
  function rememberDir(dir) {
    const clean = typeof dir === 'string' ? dir.trim() : '';
    if (!clean) return;
    try {
      const next = [clean, ...readSavedDirs().filter(item => item.toLowerCase() !== clean.toLowerCase())].slice(0, 8);
      window.localStorage?.setItem(DB_DIRS_KEY, JSON.stringify(next));
    } catch (_) {}
  }
  function parentDir(path) {
    const clean = path.replace(/[\\/]+$/, '');
    const cut = Math.max(clean.lastIndexOf('\\'), clean.lastIndexOf('/'));
    return cut > 0 ? clean.slice(0, cut) : '';
  }
  function shortDir(dir) {
    const parts = dir.replace(/[\\/]+$/, '').split(/[\\/]+/).filter(Boolean);
    return parts.slice(-2).join('\\') || dir;
  }
  function isInsideDir(filePath, dir) {
    const norm = value => value.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
    const target = norm(filePath), base = norm(dir);
    return Boolean(base) && (target === base || target.startsWith(`${base}/`));
  }
  function formatDbLabel(db) {
    const count = Number.isFinite(db.session_count) ? `${db.session_count} 个会话` : '手动指定';
    return `${db.name} · ${count}${db.directory ? ` · ${shortDir(db.directory)}` : ''}`;
  }
  function renderDatabaseChoices(selectedPath = '') {
    const choice = $('database-choice');
    if (!choice) return;
    choice.replaceChildren();
    if (!discoveredDatabases.length) {
      const option = document.createElement('option');
      option.value = '';
      option.textContent = '未在默认目录发现 Wiesler 证据库';
      choice.append(option);
      choice.value = '';
      choice.title = '';
      choice.disabled = true;
      return;
    }
    for (const db of discoveredDatabases) {
      const option = document.createElement('option');
      option.value = db.path;
      option.title = db.path;
      option.textContent = formatDbLabel(db);
      choice.append(option);
    }
    choice.disabled = false;
    const active = selectedPath && discoveredDatabases.some(db => db.path === selectedPath) ? selectedPath : discoveredDatabases[0].path;
    choice.value = active;
    choice.title = active;
  }
  function syncManualChoice(database, sessionCount) {
    if (!database) return;
    const existing = discoveredDatabases.find(db => db.path === database);
    if (existing) {
      existing.session_count = sessionCount;
    } else {
      const cut = Math.max(database.lastIndexOf('\\'), database.lastIndexOf('/'));
      discoveredDatabases = [{
        path: database,
        name: cut >= 0 ? database.slice(cut + 1) : database,
        directory: cut > 0 ? database.slice(0, cut) : '',
        session_count: sessionCount,
      }, ...discoveredDatabases];
    }
    renderDatabaseChoices(database);
  }
  async function loadSessions(database, options = {}) {
    const token = ++dbGeneration;
    loadedDatabase = '';
    $('session').replaceChildren();
    $('session').disabled = true;
    $('diagnose').disabled = true;
    $('report').textContent = '';
    $('db-status').textContent = '正在读取会话…';
    try {
      if (!invoke) throw new Error('请使用桌面程序');
      const list = await invoke('sessions', {database});
      if (token !== dbGeneration) return;
      loadedDatabase = database;
      $('database').value = database;
      rememberDir(parentDir(database));
      syncManualChoice(database, list.length);
      const ordered = [...list].reverse();
      for (const s of ordered) {
        const option = document.createElement('option');
        option.value = s.session_id;
        option.textContent = `${new Date(s.started_at_utc).toLocaleString()} · ${s.event_count} 事件 · ${s.session_id}`;
        $('session').append(option);
      }
      $('session').value = ordered[0]?.session_id || '';
      $('session').disabled = !ordered.length;
      $('diagnose').disabled = !ordered.length;
      if (options.discoveredCount > 0) {
        $('db-status').textContent = ordered.length
          ? `已发现 ${options.discoveredCount} 个证据库 · 当前含 ${ordered.length} 个会话`
          : `已发现 ${options.discoveredCount} 个证据库 · 当前库尚无会话`;
      } else {
        $('db-status').textContent = ordered.length ? `已读取 ${ordered.length} 个会话` : '数据库尚无会话';
      }
    } catch(error) {
      if (token === dbGeneration) $('db-status').textContent = `读取失败：${String(error)}`;
    }
  }
  async function discoverDatabases({force = false, extraDir = ''} = {}) {
    if (!force && (dbDiscoveredOnce || loadedDatabase || dbDiscoverBusy)) return;
    if (!invoke) { $('db-status').textContent = '请使用桌面程序'; return; }
    // 递增代际号，确保手动读取或重新扫描时丢弃过期发现结果。 / Supersede in-flight discovery on rescan or manual load.
    const token = ++dbGeneration;
    const previous = loadedDatabase;
    dbDiscoverBusy = true;
    if ($('db-discover')) $('db-discover').disabled = true;
    $('db-status').textContent = '正在自动发现本机证据库…';
    const extraDirs = [...new Set([extraDir.trim(), ...readSavedDirs()].filter(Boolean))];
    try {
      const report = await invoke('discover_databases', {extraDirs});
      if (token !== dbGeneration) return;
      dbDiscoveredOnce = true;
      discoveredDatabases = Array.isArray(report?.databases) ? report.databases : [];
      const scannedCount = Array.isArray(report?.scanned_dirs) ? report.scanned_dirs.length : 0;
      const inExtraDir = extraDir ? discoveredDatabases.find(db => isInsideDir(db.path, extraDir)) : null;
      if (extraDir && inExtraDir) rememberDir(extraDir);
      const chosen = inExtraDir?.path || (previous && discoveredDatabases.some(db => db.path === previous) ? previous : discoveredDatabases[0]?.path) || '';
      renderDatabaseChoices(chosen);
      if (extraDir && !inExtraDir) {
        if ($('database-manual')) $('database-manual').open = true;
        $('db-status').textContent = '未在指定目录发现 Wiesler 证据库';
        return;
      }
      if (!chosen) {
        loadedDatabase = '';
        $('session').replaceChildren();
        $('session').disabled = true;
        $('diagnose').disabled = true;
        $('report').textContent = '';
        if ($('database-manual')) $('database-manual').open = true;
        $('db-status').textContent = `未发现证据库（已扫描 ${scannedCount} 个目录），可手动指定目录或文件`;
        return;
      }
      if ($('database-manual') && !extraDir) $('database-manual').open = false;
      await loadSessions(chosen, {discoveredCount: discoveredDatabases.length});
    } catch(error) {
      if (token === dbGeneration) {
        if ($('database-manual')) $('database-manual').open = true;
        $('db-status').textContent = `自动发现失败：${String(error)}`;
      }
    } finally {
      dbDiscoverBusy = false;
      if ($('db-discover')) $('db-discover').disabled = false;
    }
  }
  if ($('database-choice')) {
    $('database-choice').onchange = () => {
      const selectedPath = $('database-choice').value;
      $('database-choice').title = selectedPath;
      if (selectedPath) loadSessions(selectedPath, {discoveredCount: discoveredDatabases.length});
    };
  }
  if ($('db-discover')) {
    $('db-discover').onclick = () => discoverDatabases({force: true});
  }
  $('database').oninput = () => { dbGeneration++; loadedDatabase = ''; $('session').replaceChildren(); $('session').disabled = true; $('diagnose').disabled = true; $('report').textContent = ''; $('db-status').textContent = '路径已变更，请重新读取会话。'; };
  $('database-form').onsubmit = async e => {
    e.preventDefault();
    const database = $('database').value.trim();
    if (!database) return;
    if (!/\.(sqlite3|sqlite|db)$/i.test(database)) {
      await discoverDatabases({force: true, extraDir: database});
      return;
    }
    await loadSessions(database);
  };
  $('session').onchange = () => { dbGeneration++; $('report').textContent=''; $('diagnose').disabled=false; };
  $('diagnose').onclick = async () => {
    const token = ++dbGeneration; $('diagnose').disabled=true; $('report').textContent=''; $('db-status').textContent='正在生成诊断…';
    try { const report=await invoke('diagnose',{database:loadedDatabase,session:$('session').value}); if(token===dbGeneration){$('report').textContent=report;$('db-status').textContent='诊断已生成';} }
    catch(error){if(token===dbGeneration)$('db-status').textContent=`诊断失败：${String(error)}`;}
    finally{if(token===dbGeneration)$('diagnose').disabled=false;}
  };
  let lossRunning = false, lossGeneration = 0, lossTimer, lossBusy = false, lossSamples = [];
  let activePerfMode = window.QualityModel?.resolvePerformanceMode?.('balanced') || {
    icmpIntervalMs: 1000,
    rfSteadyPollMs: 3000,
    rfEscalatedPollMs: 3000
  };
  let lastRfReport = null;

  function computeLossStats() {
    const valid = lossSamples.filter(s => s.outcome === 'reply' || s.outcome === 'lost');
    const replies = valid.filter(s => s.outcome === 'reply');
    const lost = valid.length - replies.length;
    const timings = replies.map(s => s.rtt_ms).filter(n => Number.isFinite(n) && n >= 0);
    return {
      running: lossRunning,
      target: ($('loss-target')?.value || '1.1.1.1').trim() || '1.1.1.1',
      sent: valid.length,
      received: replies.length,
      lost,
      errors: lossSamples.length - valid.length,
      lossPercent: valid.length ? (100 * lost / valid.length) : null,
      avgRttMs: timings.length ? (timings.reduce((a, b) => a + b, 0) / timings.length) : null,
      samples: lossSamples.slice()
    };
  }

  function renderLoss() {
    const stats = computeLossStats();
    $('loss-rate').textContent = stats.sent ? `${stats.lossPercent.toFixed(1)}%` : '—';
    $('loss-counts').textContent = `发送 ${stats.sent} · 收到 ${stats.received} · 未回包 ${stats.lost} · 探测错误 ${stats.errors}`;
    $('loss-rtt').textContent = `平均延迟 ${stats.avgRttMs != null ? stats.avgRttMs.toFixed(1) + ' ms' : '—'}`;
    notifyChange();
  }
  function isValidUnicastIpv4(targetIp) {
    const parts = targetIp.split('.');
    return parts.length === 4
      && !parts.some(p => !/^(0|[1-9]\d{0,2})$/.test(p) || Number(p) > 255)
      && Number(parts[0]) < 224
      && targetIp !== '0.0.0.0';
  }
  function stopLoss(message = '已停止 · 保留最后结果') {
    lossRunning = false; lossGeneration++; clearTimeout(lossTimer);
    $('loss-target').disabled = false; $('loss-start').disabled = false; $('loss-stop').disabled = true;
    $('loss-status').textContent = message;
    notifyChange();
  }
  async function pollLoss(token, targetIp) {
    if (!lossRunning || token !== lossGeneration) return;
    // 停止后仍在结束的原生请求不与新请求重叠。 / Drain an old native call before starting another.
    if (lossBusy) { lossTimer = setTimeout(()=>pollLoss(token,targetIp),250); return; }
    lossBusy = true;
    let result;
    try { result = await invoke('ping', {targetIp}); }
    catch(error) { result = {outcome:'error', rtt_ms:null, message:error?.message || String(error)}; }
    finally { lossBusy = false; }
    if (!lossRunning || token !== lossGeneration) return;
    lossSamples.push(result); lossSamples = lossSamples.slice(-60); renderLoss();
    $('loss-status').textContent = `${targetIp} · ${new Date().toLocaleTimeString()} · ${result.message}`;
    lossTimer = setTimeout(()=>pollLoss(token,targetIp), activePerfMode.icmpIntervalMs || 1000);
  }
  function startLoss(targetOverride) {
    if (lossRunning) return true;
    if (!invoke) { $('loss-status').textContent = '请在 Wiesler 桌面程序中启动监控'; return false; }
    if (typeof targetOverride === 'string' && targetOverride.trim() && $('loss-target')) {
      $('loss-target').value = targetOverride.trim();
    }
    const targetIp = $('loss-target').value.trim();
    if (!isValidUnicastIpv4(targetIp)) {
      $('loss-status').textContent = '请输入单播 IPv4 地址'; return false;
    }
    lossGeneration++; lossRunning = true; lossSamples = []; renderLoss();
    $('loss-target').disabled = true; $('loss-start').disabled = true; $('loss-stop').disabled = false;
    $('loss-status').textContent = `${targetIp} · 正在探测…`;
    pollLoss(lossGeneration,targetIp);
    return true;
  }
  function setLossTarget(targetIp) {
    const clean = typeof targetIp === 'string' ? targetIp.trim() : '';
    if (!clean || !isValidUnicastIpv4(clean)) return false;
    const wasRunning = lossRunning;
    if (wasRunning) stopLoss('目标已切换，重新探测…');
    if ($('loss-target')) $('loss-target').value = clean;
    lossSamples = [];
    renderLoss();
    if (wasRunning) startLoss(clean);
    return true;
  }
  async function runSinglePingBurst(targetOverride, count = 3) {
    if (!invoke) return computeLossStats();
    const targetIp = (typeof targetOverride === 'string' && targetOverride.trim() ? targetOverride.trim() : $('loss-target')?.value?.trim()) || '1.1.1.1';
    if (!isValidUnicastIpv4(targetIp)) return computeLossStats();
    if ($('loss-target') && !lossRunning) $('loss-target').value = targetIp;
    const burstToken = lossGeneration;
    for (let i = 0; i < count; i++) {
      if (lossRunning || burstToken !== lossGeneration) break;
      let result;
      try { result = await invoke('ping', { targetIp }); }
      catch (error) { result = { outcome: 'error', rtt_ms: null, message: error?.message || String(error) }; }
      if (lossRunning || burstToken !== lossGeneration) break;
      lossSamples.push(result);
      lossSamples = lossSamples.slice(-60);
      renderLoss();
      $('loss-status').textContent = `${targetIp} · ${new Date().toLocaleTimeString()} · ${result.message}`;
    }
    return computeLossStats();
  }
  $('loss-form').onsubmit = e => {
    e.preventDefault();
    startLoss();
  };
  $('loss-stop').onclick = () => stopLoss();
  let rfRunning = false, rfGeneration = 0, rfTimer, rfBusy = false;
  let controllerHealth = { connected: false, healthy: false, error: null };

  function currentLinkContext() {
    const valid = lossSamples.filter(s => s.outcome === 'reply' || s.outcome === 'lost');
    const replies = valid.filter(s => s.outcome === 'reply');
    const lost = valid.length - replies.length;
    const timings = replies.map(s => s.rtt_ms).filter(n => Number.isFinite(n) && n >= 0);
    return {
      icmp_sample_count: valid.length,
      icmp_loss_percent: valid.length ? (100 * lost / valid.length) : null,
      icmp_avg_rtt_ms: timings.length ? (timings.reduce((a, b) => a + b, 0) / timings.length) : null,
      controller_connected: controllerHealth.connected,
      controller_healthy: controllerHealth.healthy,
      last_controller_error: controllerHealth.error
    };
  }

  function renderRfReport(report) {
    if (!$('rf-severity-badge')) return;
    const badge = $('rf-severity-badge');
    badge.textContent = report.severity_label || '待开始';
    badge.setAttribute('data-tone',
      report.severity === 'severe' || report.severity === 'interference' ? 'danger'
        : report.severity === 'watch' ? 'warn'
        : report.severity === 'clear' ? 'ok' : 'neutral'
    );

    const w = report.wifi || {};
    $('rf-wifi-value').textContent = w.available
      ? `${w.rssi_dbm != null ? `${w.rssi_dbm} dBm` : (w.signal_percent != null ? `${w.signal_percent}%` : '已连接')}${w.bottleneck ? ' · 瓶颈' : ' · 正常'}`
      : '非 Wi-Fi';
    $('rf-wifi-sub').textContent = w.summary || '未读取到本机 Wi-Fi 状态';

    $('rf-adb-value').textContent = report.adb_connected
      ? `新鲜 ${report.fresh_frames} · 去重 ${report.cached_duplicates}`
      : '未连接 ADB';
    $('rf-adb-sub').textContent = report.frozen_slots > 0
      ? `检测到 ${report.frozen_slots} 张卡缓存冻结（已过滤重复帧）· ${report.adb_note}`
      : (report.adb_note || '');

    $('rf-score-value').textContent = report.slots?.length ? `${report.score} / 100` : '—';
    $('rf-score-sub').textContent = report.slots?.length
      ? `${report.assessment_title} · 剪刀差 ${report.scissors_alerts} · 失步 ${report.sync_loss_alerts} · 乒乓 ${report.pingpong_alerts}`
      : (report.assessment_title || '等待双卡基带样本');

    const slotsEl = $('rf-slots');
    if (slotsEl) {
      slotsEl.replaceChildren();
      const slots = Array.isArray(report.slots) ? report.slots : [];
      slotsEl.hidden = slots.length === 0;
      for (const s of slots) {
        const card = document.createElement('div');
        card.className = 'rf-slot-card';
        if (s.active_flags?.includes('RF_INTERFERENCE') || s.active_flags?.includes('RF_SYNC_LOSS')) {
          card.dataset.tone = 'danger';
        } else if (s.active_flags?.includes('CELL_PINGPONG') || s.cache_frozen) {
          card.dataset.tone = 'warn';
        }
        const head = document.createElement('div');
        head.className = 'rf-slot-head';
        const title = document.createElement('strong');
        title.textContent = `卡 ${s.phone_id + 1} · ${s.operator} · ${s.band}${s.carrier_aggregation ? ' CA' : ''}`;
        const rat = document.createElement('span');
        rat.className = 'rf-sub';
        rat.textContent = s.in_service ? `${s.rat}${s.arfcn ? ` #${s.arfcn}` : ''}` : 'NO_SIGNAL';
        head.append(title, rat);

        const metrics = document.createElement('div');
        metrics.className = 'rf-slot-metrics';
        const corr = Number.isFinite(s.rsrp_sinr_corr) ? `r=${s.rsrp_sinr_corr.toFixed(2)}` : 'r=—';
        metrics.textContent = `RSRP ${s.rsrp_dbm ?? '—'} dBm · RSRQ ${s.rsrq_db ?? '—'} dB · SINR ${s.sinr_db ?? '—'} dB · 60s切换 ${s.pingpong_60s} · ${corr}`;

        card.append(head, metrics);
        if (Array.isArray(s.active_flags) && s.active_flags.length) {
          const flags = document.createElement('div');
          flags.className = 'rf-flags';
          flags.textContent = `告警：${s.active_flags.join(' / ')}`;
          card.append(flags);
        }
        slotsEl.append(card);
      }
    }

    const attr = report.attribution || {};
    const box = $('rf-attribution-box');
    if (box) {
      box.dataset.cause = attr.cause || 'idle';
      $('rf-attr-badge').textContent = `置信度 · ${attr.confidence || '—'}`;
      $('rf-attr-title').textContent = attr.title || '等待三段对照采样';
      $('rf-attr-detail').textContent = attr.detail || '';
      $('rf-attr-suggestion').textContent = attr.suggestion ? `建议：${attr.suggestion}` : '';
    }

    const events = Array.isArray(report.recent_events) ? report.recent_events : [];
    if ($('rf-events-count')) $('rf-events-count').textContent = String(events.length);
    const evBox = $('rf-events');
    if (evBox) {
      evBox.replaceChildren();
      if (!events.length) {
        evBox.textContent = '尚无射频告警事件';
      } else {
        for (const ev of events.slice(0, 12)) {
          const row = document.createElement('div');
          row.className = 'rf-event-row';
          const when = new Date(ev.observed_at_ms).toLocaleTimeString();
          row.textContent = `${when} · [${ev.code}] ${ev.title} — ${ev.detail}`;
          evBox.append(row);
        }
      }
    }
  }

  function stopRf(message = '已停止') {
    rfRunning = false;
    rfGeneration++;
    clearTimeout(rfTimer);
    if ($('rf-start')) $('rf-start').disabled = false;
    if ($('rf-stop')) $('rf-stop').disabled = true;
    if ($('rf-severity-badge')) $('rf-severity-badge').textContent = message;
  }

  async function pollRf(token) {
    if (!rfRunning || token !== rfGeneration) return;
    if (rfBusy) {
      rfTimer = setTimeout(() => pollRf(token), 400);
      return;
    }
    rfBusy = true;
    try {
      const report = await invoke('rf_probe', { link: currentLinkContext() });
      if (!rfRunning || token !== rfGeneration) return;
      lastRfReport = report;
      renderRfReport(report);
      notifyChange();
    } catch (error) {
      if (rfRunning && token === rfGeneration && $('rf-adb-sub')) {
        $('rf-adb-sub').textContent = `监测失败：${error?.message || String(error)}`;
      }
    } finally {
      rfBusy = false;
      if (rfRunning && token === rfGeneration) {
        const escalated = lastRfReport?.severity === 'severe' || lastRfReport?.severity === 'interference';
        const delay = escalated
          ? (activePerfMode.rfEscalatedPollMs || 3000)
          : (activePerfMode.rfSteadyPollMs || 6000);
        rfTimer = setTimeout(() => pollRf(token), delay);
      }
    }
  }

  async function probeRfOnce() {
    if (!invoke || rfBusy) return lastRfReport;
    rfBusy = true;
    try {
      const report = await invoke('rf_probe', { link: currentLinkContext() });
      lastRfReport = report;
      renderRfReport(report);
      notifyChange();
      return report;
    } catch (_) {
      return lastRfReport;
    } finally {
      rfBusy = false;
    }
  }

  if ($('rf-start')) {
    $('rf-start').onclick = () => {
      if (rfRunning) return;
      if (!invoke) {
        if ($('rf-adb-sub')) $('rf-adb-sub').textContent = '请在 Wiesler 桌面程序中启动射频监测';
        return;
      }
      rfGeneration++;
      rfRunning = true;
      $('rf-start').disabled = true;
      $('rf-stop').disabled = false;
      $('rf-severity-badge').textContent = '采集中…';
      pollRf(rfGeneration);
    };
  }
  if ($('rf-stop')) {
    $('rf-stop').onclick = () => stopRf('已停止');
  }
  if ($('rf-reset')) {
    $('rf-reset').onclick = async () => {
      if (invoke) {
        try { await invoke('rf_reset'); } catch (_) {}
      }
      if ($('rf-slots')) { $('rf-slots').replaceChildren(); $('rf-slots').hidden = true; }
      if ($('rf-events')) $('rf-events').textContent = '尚无射频告警事件';
      if ($('rf-events-count')) $('rf-events-count').textContent = '0';
      if ($('rf-score-value')) $('rf-score-value').textContent = '—';
      if ($('rf-adb-value')) $('rf-adb-value').textContent = '已清空';
      lastRfReport = null;
      notifyChange();
    };
  }

  // 隐藏时停止，恢复由用户开启，避免把休眠间隔算成丢包或缓存冻结。 / Stop hidden monitoring without inventing loss or freeze.
  document.addEventListener('visibilitychange',()=>{
    if(document.hidden && lossRunning)stopLoss('窗口已隐藏，监控停止 · 点击开始重新采样');
    if(document.hidden && rfRunning)stopRf('窗口已隐藏，已暂停');
  });

  function setControllerHealth(next) {
    controllerHealth = {
      connected: Boolean(next?.connected),
      healthy: Boolean(next?.healthy),
      error: next?.error || null
    };
    notifyChange();
  }

  function setPerformanceMode(mode) {
    if (!mode) return;
    activePerfMode = {
      icmpIntervalMs: mode.icmpIntervalMs || 1000,
      rfSteadyPollMs: mode.rfSteadyPollMs || 6000,
      rfEscalatedPollMs: mode.rfEscalatedPollMs || 3000
    };
  }

  function onChange(listener) {
    if (typeof listener === 'function') changeListeners.add(listener);
  }

  window.MonitorTools = {
    appendRequestLog,
    getRequestLogs: () => requestLogs.slice(),
    discoverDatabases,
    setControllerHealth,
    getLossStats: computeLossStats,
    setLossTarget,
    startLoss,
    stopLoss,
    runSinglePingBurst,
    getRfReport: () => lastRfReport,
    probeRfOnce,
    setPerformanceMode,
    onChange
  };
})();
