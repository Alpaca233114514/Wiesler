(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const invoke = window.__TAURI__?.core?.invoke;
  const requestLogs = [];
  function appendRequestLog(code, error) {
    const status = Number.isInteger(code) && code >= 100 && code <= 599 ? code : null;
    const failed = Boolean(error) || status >= 400;
    const result = failed ? '失败' : status >= 200 && status < 300 ? '成功' : status >= 300 ? '重定向' : '未知';
    requestLogs.unshift({time:new Date().toLocaleTimeString(), status, result, error});
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
  function renderLoss() {
    const valid = lossSamples.filter(s => s.outcome === 'reply' || s.outcome === 'lost');
    const replies = valid.filter(s => s.outcome === 'reply');
    const lost = valid.length - replies.length;
    const timings = replies.map(s => s.rtt_ms).filter(n => Number.isFinite(n) && n >= 0);
    $('loss-rate').textContent = valid.length ? `${(100 * lost / valid.length).toFixed(1)}%` : '—';
    $('loss-counts').textContent = `发送 ${valid.length} · 收到 ${replies.length} · 未回包 ${lost} · 探测错误 ${lossSamples.length - valid.length}`;
    $('loss-rtt').textContent = `平均延迟 ${timings.length ? (timings.reduce((a,b)=>a+b,0)/timings.length).toFixed(1) + ' ms' : '—'}`;
  }
  function stopLoss(message = '已停止 · 保留最后结果') {
    lossRunning = false; lossGeneration++; clearTimeout(lossTimer);
    $('loss-target').disabled = false; $('loss-start').disabled = false; $('loss-stop').disabled = true;
    $('loss-status').textContent = message;
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
    lossTimer = setTimeout(()=>pollLoss(token,targetIp),1000);
  }
  $('loss-form').onsubmit = e => {
    e.preventDefault();
    if (lossRunning) return;
    if (!invoke) { $('loss-status').textContent = '请在 Wiesler 桌面程序中启动监控'; return; }
    const targetIp = $('loss-target').value.trim(), parts = targetIp.split('.');
    if (parts.length !== 4 || parts.some(p=>! /^(0|[1-9]\d{0,2})$/.test(p) || Number(p)>255) || Number(parts[0])>=224 || targetIp==='0.0.0.0') {
      $('loss-status').textContent = '请输入单播 IPv4 地址'; return;
    }
    lossGeneration++; lossRunning = true; lossSamples = []; renderLoss();
    $('loss-target').disabled = true; $('loss-start').disabled = true; $('loss-stop').disabled = false;
    $('loss-status').textContent = `${targetIp} · 正在探测…`;
    pollLoss(lossGeneration,targetIp);
  };
  $('loss-stop').onclick = () => stopLoss();
  // 隐藏时停止，恢复由用户开启，避免把休眠间隔算成丢包。 / Stop hidden monitoring without inventing loss.
  document.addEventListener('visibilitychange',()=>{if(document.hidden && lossRunning)stopLoss('窗口已隐藏，监控停止 · 点击开始重新采样');});

  window.MonitorTools = {appendRequestLog, discoverDatabases};
})();
