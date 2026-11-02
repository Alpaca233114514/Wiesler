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
  let dbGeneration = 0, loadedDatabase = '';
  $('database').oninput = () => { dbGeneration++; loadedDatabase = ''; $('session').replaceChildren(); $('session').disabled = true; $('diagnose').disabled = true; $('report').textContent = ''; $('db-status').textContent = '路径已变更，请重新读取会话。'; };
  $('database-form').onsubmit = async e => {
    e.preventDefault(); const token = ++dbGeneration; loadedDatabase = ''; $('session').replaceChildren(); $('session').disabled = true; $('diagnose').disabled = true; $('report').textContent = ''; $('db-status').textContent = '正在读取…';
    try {
      if (!invoke) throw new Error('请使用桌面程序');
      const database = $('database').value.trim(), sessions = await invoke('sessions',{database});
      if (token !== dbGeneration) return;
      loadedDatabase = database;
      for (const s of sessions.reverse()) { const option=document.createElement('option'); option.value=s.session_id; option.textContent=`${new Date(s.started_at_utc).toLocaleString()} · ${s.event_count} 事件 · ${s.session_id}`; $('session').append(option); }
      $('session').disabled = !sessions.length; $('diagnose').disabled = !sessions.length; $('db-status').textContent = sessions.length ? `已读取 ${sessions.length} 个会话` : '数据库尚无会话';
    } catch(error) { if (token === dbGeneration) $('db-status').textContent = `读取失败：${String(error)}`; }
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

  window.MonitorTools = {appendRequestLog};
})();
