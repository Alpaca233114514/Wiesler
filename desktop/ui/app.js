const $ = id => document.getElementById(id);
const invoke = window.__TAURI__?.core.invoke;
let connections = [], previous = null, history = [], running = false, generation = 0, timer;
const bytes = n => { if (n == null) return '—'; const i = Math.min(3, Math.floor(Math.log(Math.max(1, n)) / Math.log(1024))); return `${(n / 1024 ** i).toFixed(i ? 2 : 0)} ${['B','KB','MB','GB'][i]}`; };
const target = c => `${c.metadata.host || c.metadata.destination_ip || '未知'}:${c.metadata.destination_port ?? '?'}`;
function render() {
  const q = $('search').value.toLowerCase(), protocol = $('protocol').value;
  const visible = connections.filter(c => JSON.stringify(c).toLowerCase().includes(q) && (!protocol || c.metadata.network?.toUpperCase() === protocol));
  $('count').textContent = ` / ${visible.length} 条`;
  $('rows').replaceChildren();
  for (const c of visible) {
    const tr = document.createElement('tr');
    [c.metadata.process || '未知进程', target(c), c.metadata.network?.toUpperCase() || '未知', c.chains.join(' → ') || '未知', bytes(c.download), running ? '最近快照' : '已暂停快照'].forEach((value, i) => {
      const td = document.createElement('td');
      if (i === 0) {
        const button = document.createElement('button'); button.className = 'connection'; button.textContent = value; button.onclick = () => detail(c);
        const small = document.createElement('small'); small.textContent = `PID ${c.metadata.pid ?? '未知'}`; td.append(button, small);
      } else td.textContent = value;
      tr.append(td);
    });
    $('rows').append(tr);
  }
  if (!visible.length) { const tr = document.createElement('tr'), td = document.createElement('td'); td.colSpan = 6; td.textContent = previous ? '没有匹配的连接' : '尚无有效快照'; tr.append(td); $('rows').append(tr); }
}
function detail(c) {
  $('detail-title').textContent = c.metadata.process || '未知进程';
  const dl = document.createElement('dl');
  Object.entries({'连接 ID':c.id,'进程 ID':c.metadata.pid ?? '未知','目标地址':target(c),'源地址':`${c.metadata.source_ip || '未知'}:${c.metadata.source_port ?? '?'}`,'协议':c.metadata.network || '未知','规则':c.rule || '未知','代理链（原序）':c.chains.join(' → ') || '未知','下载 / 上传':`${bytes(c.download)} / ${bytes(c.upload)}`,'开始时间':c.start || '未知'}).forEach(([k,v]) => { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = k; dd.textContent = v; dl.append(dt,dd); });
  $('detail-body').replaceChildren(dl); $('detail').showModal();
}
function chart() {
  const max = Math.max(1, ...history.flatMap(p => p || [0,0]));
  for (const [index,id] of ['download','upload'].entries()) {
    let connected = false;
    const path = history.map((p,i) => { if (!p) { connected = false; return ''; } const command = connected ? 'L' : 'M'; connected = true; return `${command}${(60 - history.length + i) * 800 / 59},${165 - p[index] / max * 155}`; }).join(' ');
    $(id).setAttribute('d', path);
  }
  $('area').setAttribute('d',''); $('axis').textContent = `${bytes(max)}/s`;
}
function reset() {
  previous = null; connections = []; history = []; chart(); render();
  for (const id of ['active','rate','upload-rate']) $(id).textContent = '—';
  $('processes').textContent = '等待快照'; $('download-total').textContent = '等待快照'; $('upload-total').textContent = '等待快照'; $('sample-time').textContent = '尚无采样';
}
async function poll(token, controller) {
  if (!running || token !== generation) return;
  try {
    const data = await invoke('snapshot', {controller});
    if (token !== generation) return;
    const dt = previous ? (data.observed_at - previous.observed_at) / 1000 : 0;
    // 缺口与计数回退重新建立基线 / Rebaseline after gaps or counter resets.
    const rate = dt > 0 && data.download_total >= previous.download_total && data.upload_total >= previous.upload_total ? [(data.download_total - previous.download_total)/dt, (data.upload_total - previous.upload_total)/dt] : null;
    history.push(rate); history = history.slice(-60); chart();
    previous = data; connections = data.connections; render();
    $('active').textContent = connections.length;
    $('processes').textContent = `已识别 ${new Set(connections.filter(c=>c.metadata.pid).map(c=>c.metadata.pid)).size} 个进程 PID`;
    $('rate').textContent = rate ? `${bytes(rate[0])}/s` : '等待差分'; $('upload-rate').textContent = rate ? `${bytes(rate[1])}/s` : '等待差分';
    $('download-total').textContent = `控制器累计 ${bytes(data.download_total)}`; $('upload-total').textContent = `控制器累计 ${bytes(data.upload_total)}`;
    $('sample-time').textContent = new Date(data.observed_at).toLocaleTimeString(); $('health').textContent = '已连接'; $('live-label').textContent = '实时观察中'; $('source-status').textContent = '只读采样中';
  } catch(error) {
    if (token !== generation) return;
    previous = null; connections = []; render(); history.push(null); history = history.slice(-60); chart();
    for (const id of ['active','rate','upload-rate']) $(id).textContent = '—';
    $('processes').textContent = '当前连接未知'; $('health').textContent = '采集异常'; $('live-label').textContent = '数据不可用';
    $('source-status').textContent = `控制器读取失败：${String(error)}；将自动重试`;
  } finally { if (running && token === generation) timer = setTimeout(()=>poll(token,controller),1000); }
}
function start() {
  if (!invoke) { $('source-status').textContent = '请在 Wiesler 桌面程序中连接后端；浏览器预览不提供实时数据。'; return; }
  clearTimeout(timer); generation++; running = true; reset(); $('health').textContent = '连接中'; $('live-label').textContent = '正在连接'; $('pause').textContent = '暂停观察';
  const controller = $('controller').value.trim(); $('route-controller').textContent = controller; poll(generation,controller);
}
$('connect-form').onsubmit = e => { e.preventDefault(); start(); };
$('pause').onclick = () => { if (!running) return start(); running = false; generation++; clearTimeout(timer); $('pause').textContent = '恢复观察'; $('health').textContent = '已暂停'; $('live-label').textContent = '已暂停 · 保留最后快照'; $('source-status').textContent = '已停止采样'; render(); };
$('close').onclick = () => $('detail').close(); $('search').oninput = render; $('protocol').onchange = render;
const pages = {overview:['运行概览','从应用到出口，让每一段连接清晰可见。'],connections:['连接观察','查看控制器报告的进程、目标与代理链。'],diagnostics:['诊断中心','从已保存的会话证据生成诊断。']};
document.querySelectorAll('nav button').forEach(b=>b.onclick=()=>{document.querySelectorAll('nav button').forEach(n=>n.classList.toggle('active',n===b));const page=b.dataset.page;$('title').textContent=pages[page][0];$('subtitle').textContent=pages[page][1];$('overview').hidden=page!=='overview';$('metrics').hidden=page!=='overview';$('connection-panel').hidden=page==='diagnostics';$('connect-form').hidden=page==='diagnostics';$('diagnostics').hidden=page!=='diagnostics'});
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
render(); chart();
