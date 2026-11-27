(() => {
  'use strict';
  const $ = id => document.getElementById(id), M = window.StudioModel, Q = window.QualityModel;
  const svgNS = 'http://www.w3.org/2000/svg';
  const invoke = window.__TAURI__?.core?.invoke;
  const readStorage = key => { try { return window.localStorage?.getItem(key) || ''; } catch (_) { return ''; } };
  const writeStorage = (key, value) => {
    try {
      if (value == null) window.localStorage?.removeItem(key);
      else window.localStorage?.setItem(key, value);
    } catch (_) {}
  };
  let frames = [], current = null, selected = null, tab = 'overview', lastInspectKey = '';
  let frozen = false, replay = null, timer, generation = 0, liveBusy = false;
  let collecting = false, activeController = '', view = 'home', rebaseline = false;
  let systemCurrent = null, systemError = null, systemBusy = false, systemTimer, controllerFrame = null;
  let egressGeneration = 0, replayFrames = null, animationEnabled = true, userPulsePreference = true;
  let discovering = false, scanPromise = null, discoveryMessage = '';
  let perfMode = Q?.resolvePerformanceMode?.(readStorage('wiesler.performanceMode')) || {
    code: 'balanced', label: '均衡模式', summary: '1s 活跃采样 · RF 异常自动提速 · 兼顾响应与占用（推荐）',
    activeTickMs: 1000, systemTickMs: 3000, icmpIntervalMs: 1000, rfSteadyPollMs: 6000, rfEscalatedPollMs: 3000, allowPulseAnimation: true
  };
  let themeMode = Q?.resolveThemeMode?.(readStorage('wiesler.theme')) || { code: 'system', label: '跟随系统' };
  let settingsTab = 'controller';
  const qualityHistory = Q?.createHistoryBuffer ? Q.createHistoryBuffer(60) : null;
  const homeState = {
    httpsProbe: null,
    dnsInspection: null,
    systemEgress: null,
    systemEgressError: null,
    proxyEgress: null,
    proxyEgressError: null,
    checkBusy: false,
    checkToken: 0
  };
  const pulseGroups = new Map();
  let scale = 1, pan = {x:0,y:0}, positions = new Map(), drag = null, ignoreClick = false;
  const text = (tag, content, cls) => {const el=document.createElement(tag);el.textContent=content;if(cls)el.className=cls;return el;};
  const svg = (tag, attrs = {}) => {const el=document.createElementNS(svgNS,tag);for(const [k,v] of Object.entries(attrs))el.setAttribute(k,v);return el;};
  function shown() {return replay || current;}
  function visible() {
    const q=$('flow-search').value.trim().toLowerCase(), protocol=$('flow-protocol').value, source=$('flow-source').value;
    return (shown()?.flows || []).filter(f => (!source || (source==='system'?f.origin==='system':f.routing===source)) && (!protocol || protocol===f.protocol) && [f.app,f.host,f.destination,f.source,f.route].some(v=>v.toLowerCase().includes(q)));
  }
  function accept(next) {
    current=next;frames.push(next);if(frames.length>60)frames.shift();
    $('snapshot-count').textContent=`${frames.length} 次快照`;
    renderMonitor();renderStatus();renderHome();
    if(!frozen && !replay) render();
  }
  function renderStatus() {
    $('source-state').textContent = discovering ? '正在扫描本机控制器…' : current?.error ? `读取失败：${current.error}` : !collecting ? (current ? '已暂停 · 保留最后快照' : discoveryMessage || '尚未连接') : document.hidden ? '窗口隐藏 · 采集暂停' : activeController ? (controllerFrame ? '实时 · Mihomo' : '正在连接代理控制器…') : discoveryMessage || '本机连接采集中';
    $('source-state').setAttribute('data-tone', current?.error ? 'danger' : (!collecting || discovering || document.hidden || !invoke) ? 'warning' : 'live');
    $('system-state').textContent=!invoke?'系统连接需使用桌面程序':systemError?`系统连接：${systemError}`:systemCurrent?`系统 TCP · ${systemCurrent.sockets.length} 条`:'正在读取系统连接…';
    $('egress-panel').hidden=!['graph','monitor'].includes(view);
    $('discover').disabled=discovering;
    $('controller-address').textContent=activeController;
    if($('settings-summary-controller')){
      $('settings-summary-controller').textContent=!activeController?'未连接':controllerFrame&&!controllerFrame.error?'已连接':'连接异常';
    }
    $('source-state').hidden=false;$('statusbar').hidden=false;
    $('capture-state').textContent = replay ? `${frozen?'已冻结':'回放中'} · ${collecting&&!document.hidden?'采集继续':'采集暂停'}` : '';
    $('capture-toggle').disabled=!invoke;
    $('capture-toggle').textContent=collecting?'暂停采集':'恢复采集';
  }
  function currentQualityInput() {
    const loss = window.MonitorTools?.getLossStats?.() || { running: false, target: '1.1.1.1', sent: 0, received: 0, lossPercent: null, avgRttMs: null, samples: [] };
    const rfReport = window.MonitorTools?.getRfReport?.() || null;
    const online = typeof window.navigator?.onLine === 'boolean' ? window.navigator.onLine : true;
    const validated = homeState.httpsProbe
      ? Boolean(homeState.httpsProbe.ok)
      : (homeState.systemEgress ? true : (homeState.systemEgressError && !online ? false : undefined));
    const transportLabel = rfReport?.wifi?.available ? 'Wi-Fi' : '本机网络';
    const flows = current?.flows || [];
    return {
      nowMs: Date.now(),
      link: { connected: online, validated, transportLabel },
      pingTarget: loss.target,
      pingRunning: loss.running,
      recentPings: loss.samples,
      lossPercent: loss.lossPercent,
      averageRttMs: loss.avgRttMs,
      pingSent: loss.sent,
      pingReceived: loss.received,
      dnsInspection: homeState.dnsInspection || activePrivacy?.dns?.value || null,
      httpsProbe: homeState.httpsProbe,
      systemEgress: homeState.systemEgress,
      systemEgressError: homeState.systemEgressError,
      proxyEgress: homeState.proxyEgress,
      proxyEgressError: homeState.proxyEgressError,
      rfReport,
      controllerEnabled: Boolean(activeController),
      controllerHealthy: Boolean(activeController && controllerFrame && !controllerFrame.error),
      activeFlowCount: current ? flows.length : null,
      proxyFlowCount: flows.filter(f => f.routing === 'proxy').length,
      recentRequestLogs: window.MonitorTools?.getRequestLogs?.() || []
    };
  }
  function renderHome() {
    if (!Q || !$('home-score')) return;
    const input = currentQualityInput();
    const snapshot = Q.evaluate(input, qualityHistory ? qualityHistory.snapshot() : []);
    if (qualityHistory && (input.pingSent > 0 || input.httpsProbe || input.rfReport || input.systemEgress)) {
      qualityHistory.record(snapshot, input.averageRttMs, input.lossPercent);
    }
    const historyPoints = qualityHistory ? qualityHistory.snapshot() : [];
    const tone = snapshot.grade?.tone || 'neutral';
    const score = snapshot.overallScore;
    $('home-score').textContent = score != null ? String(score) : '—';
    $('home-grade').textContent = snapshot.grade ? `${snapshot.grade.badge} · ${snapshot.grade.label}` : '待体检';
    $('home-grade').setAttribute('data-tone', tone);
    const arc = $('home-gauge-arc');
    if (arc) {
      const circumference = 2 * Math.PI * 48;
      const pct = score != null ? Math.max(0, Math.min(100, score)) / 100 : 0;
      arc.setAttribute('stroke-dasharray', circumference.toFixed(2));
      arc.setAttribute('stroke-dashoffset', (circumference * (1 - pct)).toFixed(2));
      arc.setAttribute('data-tone', tone);
    }
    $('home-headline').textContent = snapshot.headline;
    $('home-summary').textContent = snapshot.summary;
    $('home-advice').textContent = (score != null && score < 70) || snapshot.measuredDimensions < snapshot.totalApplicableDimensions ? snapshot.advice : '';
    const valid = current && !current.error && current.download != null;
    $('home-rate-rx').textContent = `↓ ${valid && current.rx != null ? `${M.bytes(current.rx)}/s` : '—'}`;
    $('home-rate-tx').textContent = `↑ ${valid && current.tx != null ? `${M.bytes(current.tx)}/s` : '—'}`;
    const linkPill = $('home-link-status');
    if (linkPill) {
      const linkText = !input.link.connected ? '离线' : input.link.validated === false ? '未校验' : input.link.transportLabel;
      const linkTone = !input.link.connected ? 'danger' : input.link.validated === false ? 'warn' : 'ok';
      linkPill.textContent = linkText;
      linkPill.setAttribute('data-tone', linkTone);
    }
    $('home-dim-coverage').textContent = `${snapshot.measuredDimensions}/${snapshot.totalApplicableDimensions} 维已测`;
    if ($('home-perf-badge')) $('home-perf-badge').textContent = perfMode.label;
    if ($('home-monitor-toggle')) $('home-monitor-toggle').textContent = input.pingRunning ? '停止监测' : '持续监测';
    for (const [id, ip] of [['home-target-223', '223.5.5.5'], ['home-target-111', '1.1.1.1'], ['home-target-888', '8.8.8.8']]) {
      $(id)?.setAttribute('aria-pressed', String(input.pingTarget === ip));
    }

    const dimBox = $('home-dimensions');
    if (dimBox) {
      dimBox.replaceChildren();
      for (const dim of snapshot.dimensions) {
        const cell = text('button', '', 'home-dim-cell');
        cell.type = 'button';
        cell.dataset.dim = dim.id;
        cell.setAttribute('data-tone', !dim.available ? 'neutral' : dim.score >= 80 ? 'ok' : dim.score >= 55 ? 'warn' : 'danger');
        const head = text('div', '', 'home-dim-head');
        head.append(text('span', dim.label, 'home-dim-label'), text('strong', dim.score != null ? `${dim.score}` : '—', 'home-dim-score'));
        cell.append(head, text('div', dim.primaryMetric, 'home-dim-metric'), text('div', `${dim.statusText} · ${dim.detail}`, 'home-dim-detail'));
        cell.onclick = () => {
          if (dim.id === 'access_signal' || dim.id === 'latency_stability') selectView('monitor');
          else if (dim.id === 'reachability_dns') selectView('privacy');
          else selectView('settings');
        };
        dimBox.append(cell);
      }
    }

    const scenarioBox = $('home-scenarios');
    if (scenarioBox) {
      scenarioBox.replaceChildren();
      for (const sc of snapshot.scenarios) {
        const card = text('div', '', 'home-scenario-cell');
        card.setAttribute('data-status', sc.status);
        const top = text('div', '', 'home-scenario-head');
        top.append(text('strong', sc.title), text('span', sc.statusLabel, `home-scenario-badge status-${sc.status}`));
        card.append(top, text('span', sc.detail, 'home-scenario-detail'));
        scenarioBox.append(card);
      }
    }

    const line = $('home-trend-line'), area = $('home-trend-area'), meta = $('home-trend-meta');
    if (line && area) {
      if (historyPoints.length >= 2) {
        const coords = historyPoints.map((pt, idx) => {
          const x = (idx / Math.max(1, historyPoints.length - 1)) * 800;
          const y = 112 - (Math.max(0, Math.min(100, pt.score)) / 100) * 100;
          return [x, y];
        });
        const path = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
        line.setAttribute('d', path);
        area.setAttribute('d', `${path} L${coords.at(-1)[0].toFixed(1)},116 L${coords[0][0].toFixed(1)},116 Z`);
      } else {
        line.setAttribute('d', '');
        area.setAttribute('d', '');
      }
    }
    if (meta) {
      const parts = [`${historyPoints.length} 次记录`];
      if (Number.isFinite(input.averageRttMs)) parts.push(`平均 RTT ${input.averageRttMs.toFixed(1)} ms`);
      if (snapshot.jitterMs != null) parts.push(`抖动 ${snapshot.jitterMs.toFixed(1)} ms`);
      if (Number.isFinite(input.lossPercent)) parts.push(`丢包 ${input.lossPercent.toFixed(1)}%`);
      meta.textContent = parts.join(' · ');
    }
  }
  async function runHomeQualityCheck() {
    if (homeState.checkBusy) return;
    if (!invoke) {
      if ($('home-summary')) $('home-summary').textContent = '综合体检请使用 Wiesler 桌面程序。';
      return;
    }
    homeState.checkBusy = true;
    const token = ++homeState.checkToken;
    if ($('home-check-btn')) { $('home-check-btn').disabled = true; $('home-check-btn').textContent = '体检中…'; }
    const lossStats = window.MonitorTools?.getLossStats?.();
    const tasks = [
      Promise.resolve(invoke('probe_https_204'))
        .then(res => { if (token === homeState.checkToken) { homeState.httpsProbe = res; renderHome(); } })
        .catch(err => { if (token === homeState.checkToken) { homeState.httpsProbe = { ok: false, error: err?.message || String(err) }; renderHome(); } }),
      Promise.resolve(invoke('inspect_dns'))
        .then(res => { if (token === homeState.checkToken) { homeState.dnsInspection = res; renderHome(); } })
        .catch(err => { if (token === homeState.checkToken) { homeState.dnsInspection = { error: err?.message || String(err), queries: [] }; renderHome(); } }),
      refreshEgress(),
      Promise.resolve(window.MonitorTools?.probeRfOnce?.()).catch(() => null),
      lossStats?.running
        ? Promise.resolve(lossStats)
        : Promise.resolve(window.MonitorTools?.runSinglePingBurst?.(lossStats?.target, 3)).catch(() => null)
    ];
    try { await Promise.allSettled(tasks); }
    finally {
      if (token === homeState.checkToken) {
        homeState.checkBusy = false;
        if ($('home-check-btn')) { $('home-check-btn').disabled = false; $('home-check-btn').textContent = '一键体检'; }
        renderHome();
      }
    }
  }
  function renderMonitor() {
    renderPrivacy();
    const f=current, valid=f&&!f.error&&f.download!=null, observed=f&&(systemCurrent||controllerFrame&&!controllerFrame.error);
    $('monitor-count').textContent=observed?String(f.flows.length):'—';
    $('monitor-routing').textContent=observed?`直连 ${f.flows.filter(c=>c.routing==='direct').length} · 转发 ${f.flows.filter(c=>c.routing==='proxy').length} · 系统 ${f.flows.filter(c=>c.origin==='system').length}`:'等待采样';
    $('monitor-time').textContent=f?`${time(f.time)} · ${f.error?'采集异常':'最近采样'}`:'尚无采样';
    $('monitor-rx').textContent=valid?f.rx==null?'等待差分':M.bytes(f.rx):'—';
    $('monitor-tx').textContent=valid?f.tx==null?'等待差分':M.bytes(f.tx):'—';
    $('monitor-download').textContent=valid?`累计 ${M.bytes(f.download)}`:'等待有效快照';
    $('monitor-upload').textContent=valid?`累计 ${M.bytes(f.upload)}`:'等待有效快照';
    const max=Math.max(1,...frames.flatMap(f=>[f.rx||0,f.tx||0]));
    for(const direction of ['rx','tx']){
      let connected=false;
      const segments=[];
      const path=frames.map((f,i)=>{if(f[direction]==null){connected=false;return '';}
        const x=(60-frames.length+i)*800/59, y=150-f[direction]/max*140;
        if(!connected)segments.push([]);
        segments.at(-1).push([x,y]);
        const command=connected?'L':'M';connected=true;
        return `${command}${x},${y}`;}).join(' ');
      // 仅对连续有效区间闭合底部面积，保持采样缺口断开。 / Close area polygons per contiguous segment to preserve gaps.
      const area=segments.filter(seg=>seg.length>1).map(seg=>`M${seg[0][0]},156 `+seg.map(([x,y])=>`L${x},${y}`).join(' ')+` L${seg.at(-1)[0]},156 Z`).join(' ');
      $(`trend-${direction}`).setAttribute('d',path);
      $(`trend-${direction}-area`)?.setAttribute('d',area);
    }
    $('trend-scale').textContent=`峰值 ${M.bytes(max)}/s`;
  }
  let privacyPolicy = {expectProxy:false,uploadBytesPerSecond:1024*1024}, privacyEnvironment = null;
  const expandedPrivacy = new Set();
  let activePrivacy = null, privacyRunToken = 0, privacyRunBusy = false;
  let dnsCapture = null, dnsBusy = false, dnsPolling = false, dnsTimer = null, dnsRevision = 0, dnsSeconds = 30, dnsPolicy = 'proxy_required', dnsFilter = 'summary', dnsError = '', dnsProbedRun = null;
  const dnsPhases = {started:'开始查询',completed:'解析完成',send:'发送查询',receive:'接收响应',cache_lookup:'缓存查找',cache_result:'缓存结果',network_query:'缓存查找',network_result:'网络结果',result:'解析结果',query:'查询报文',response:'响应报文',encrypted_query:'加密查询',encrypted_candidate:'853 加密'};
  const dnsTypeNames = {1:'A',2:'NS',5:'CNAME',12:'PTR',15:'MX',16:'TXT',28:'AAAA',65:'HTTPS'};
  const dnsRouteLabels = {physical:'海外泄漏',domestic_direct:'国内直连',tunnel:'隧道代理',loopback:'本地代理',encrypted:'加密 DNS',cache:'系统缓存',system:'系统解析',unknown:'未知接口'};
  const dnsRegionLabels = {overseas:'海外',domestic:'国内',local:'内网',encrypted:'加密'};
  const dnsStatusBadges = {leak:'存在泄漏',not_observed:'正常',observed:'海外直出',incomplete:'采集异常',unavailable:'权限不足',no_sample:'暂无样本',awaiting_authorization:'待授权',cancelled:'已取消',starting:'启动中',idle:'待开始'};
  const dnsEncryptedTransports = new Set(['DoH','DoT','DoT (853)','DoQ (853)','TLS/853 candidate','QUIC/853 candidate']);
  function isProxyTunnelIp(ip){return /^198\.1[89]\./.test(ip||'');}
  function fallbackDnsSummary(r, a){
    const evidence=new Set(a.evidenceIds||[]);
    const resolvers=new Map(), queries=new Map(), domains=new Set();
    for(const o of r.observations||[]){
      if(o.domain)domains.add(o.domain.toLowerCase().replace(/\.$/,''));
      const isPktTx=o.source==='packet'&&o.phase==='query'&&o.direction==='tx';
      const isEnc=o.source==='windows-dns'&&['DoH','DoT'].includes(o.transport);
      const isEncPkt=o.source==='packet'&&o.phase==='encrypted_candidate'&&o.direction==='tx';
      if(!isPktTx&&!isEnc&&!isEncPkt)continue;
      const isLoop=o.resolver==='127.0.0.1'||o.resolver==='::1';
      const isProxy=isProxyTunnelIp(o.resolver);
      const routeKind=isEnc||isEncPkt?'encrypted':isLoop?'loopback':isProxy?'tunnel':o.interface?.hardware===true?'physical':o.interface?.hardware===false?'tunnel':'unknown';
      const ifaceName=isProxy&&o.interface?.hardware===true?'代理 TUN DNS':(o.interface?.name||(isLoop?'Loopback':'未知接口'));
      const resolver=o.resolver||'未知解析器';
      const rKey=`${routeKind}|${resolver}|${ifaceName}`;
      if(!resolvers.has(rKey))resolvers.set(rKey,{resolver,interfaceName:ifaceName,routeKind,queries:0,domains:[]});
      const re=resolvers.get(rKey);re.queries++;
      if(o.domain&&re.domains.length<5&&!re.domains.includes(o.domain))re.domains.push(o.domain);
      const domain=o.domain||'DoT/DoQ 加密连接 (853)';
      const region=isEncPkt?'encrypted':(/\.cn$/i.test(domain)?'domestic':'overseas');
      const qKey=`${domain.toLowerCase()}|${routeKind}|${resolver}`;
      if(!queries.has(qKey))queries.set(qKey,{domain,region,queryTypes:[],routeKind,resolver:o.resolver,interfaceName:ifaceName,process:o.process||null,pid:o.pid||null,answers:[],transport:o.transport,count:0,lastSeen:o.observedAt,isLeak:evidence.has(o.id)});
      const qe=queries.get(qKey);qe.count++;qe.lastSeen=Math.max(qe.lastSeen,o.observedAt);
      if(o.queryType&&!qe.queryTypes.includes(o.queryType))qe.queryTypes.push(o.queryType);
      if(evidence.has(o.id))qe.isLeak=true;
      for(const ans of o.answers||[])if(!qe.answers.includes(ans)&&qe.answers.length<6)qe.answers.push(ans);
    }
    return {resolvers:[...resolvers.values()],queries:[...queries.values()],uniqueDomains:domains.size};
  }
  function setMetric(id, value, tone){
    const el=$(id);if(!el)return;
    el.textContent=String(value);
    el.parentElement?.setAttribute('data-tone',tone);
  }
  function maybeAutoProbe(r){
    if(!invoke||!r?.running||r.packetState!=='active'||r.eventState!=='active')return;
    if(dnsProbedRun===r.startedAt)return;
    dnsProbedRun=r.startedAt;
    Promise.resolve(invoke('dns_capture_probe')).catch(()=>{});
  }
  function renderDns(){
    const r=dnsCapture?.report, a=dnsCapture?.assessment;
    $('dns-start').disabled=dnsBusy||!!r?.running||!invoke;$('dns-stop').disabled=dnsBusy||!r?.running;
    if($('dns-probe'))$('dns-probe').disabled=dnsBusy||!r?.running||r?.packetState!=='active'||!invoke;
    $('dns-policy-proxy').setAttribute('aria-pressed',String(dnsPolicy==='proxy_required'));
    $('dns-policy-observe').setAttribute('aria-pressed',String(dnsPolicy==='observe'));
    $('dns-policy-proxy').disabled=$('dns-policy-observe').disabled=dnsBusy;
    $('dns-duration-short').setAttribute('aria-pressed',String(dnsSeconds===30));$('dns-duration-long').setAttribute('aria-pressed',String(dnsSeconds===120));
    $('dns-duration-short').disabled=$('dns-duration-long').disabled=dnsBusy||!!r?.running;
    for(const key of ['summary','overseas','domestic','physical','encrypted','all'])$(`dns-filter-${key}`)?.setAttribute('aria-pressed',String(key===dnsFilter));
    const errors=$('dns-errors');errors.replaceChildren();if(dnsError)errors.append(text('p',dnsError));
    if(!r){
      if(dnsError)$('dns-state').textContent='DNS 操作未完成';
      $('dns-verdict-box')?.setAttribute('data-status','idle');
      if($('dns-badge'))$('dns-badge').textContent='待开始';
      $('dns-detail').textContent='点击「开始本地检查」自动区分海外与国内 DNS 出口，并核对是否绕过代理隧道。';
      for(const id of ['dns-metric-physical','dns-metric-tunnel','dns-metric-domestic','dns-metric-encrypted'])setMetric(id,'—','neutral');
      if($('dns-resolvers')){$('dns-resolvers').replaceChildren();$('dns-resolvers').hidden=true;}
      return;
    }
    maybeAutoProbe(r);
    const starting=r.packetState==='starting'||r.eventState==='starting';
    $('dns-state').textContent=`${r.running?(r.packetState==='awaiting_authorization'?'等待 Windows 授权':starting?'正在启动采集':'采集中'):'已结束'} · ${time(r.startedAt)}${r.stoppedAt?' — '+time(r.stoppedAt):''}`;
    const pending=dnsCapture.policy!==dnsPolicy;
    const statusKey=pending?'starting':starting?'starting':a.status;
    $('dns-verdict-box')?.setAttribute('data-status',statusKey);
    if($('dns-badge'))$('dns-badge').textContent=pending?'重算中':(dnsStatusBadges[statusKey]||statusKey);
    $('dns-verdict').textContent=pending?'正在按当前条件重新判断':starting?'正在启动本地采集':a.title;
    $('dns-verdict').className=!pending&&a.status==='leak'?'dns-leak':'';
    $('dns-detail').textContent=pending?'正在按所选模式重新归类已采集记录…':starting?'正在枚举本机网卡并订阅 DNS 事件…':a.detail;
    const c=r.coverage;
    const fallback=(!a.resolvers||!a.queries||a.uniqueDomains==null)?fallbackDnsSummary(r,a):null;
    const resolvers=a.resolvers?.length||!fallback?(a.resolvers||[]):fallback.resolvers;
    const summaryQueries=a.queries?.length||!fallback?(a.queries||[]):fallback.queries;
    const tunnelTotal=a.tunnelQueries ?? ((c.tunnelQueries||0)+(c.loopbackQueries||0));
    const domesticTotal=a.domesticQueries ?? (c.domesticDirectQueries||0);
    const hasSamples=c.dnsMessages>0||c.windowsEvents>0||a.encryptedEvents>0;
    setMetric('dns-metric-physical',a.physicalQueries,a.physicalQueries>0?(dnsPolicy==='proxy_required'?'danger':'warn'):hasSamples?'ok':'neutral');
    setMetric('dns-metric-tunnel',tunnelTotal,tunnelTotal>0?'ok':'neutral');
    setMetric('dns-metric-domestic',domesticTotal,domesticTotal>0?'info':'neutral');
    setMetric('dns-metric-encrypted',a.encryptedEvents,a.encryptedEvents>0?'info':'neutral');
    const resolverBox=$('dns-resolvers');
    if(resolverBox){
      resolverBox.replaceChildren();
      resolverBox.hidden=!resolvers.length;
      for(const item of resolvers){
        const chip=text('div','',`dns-resolver-chip route-${item.routeKind}`);
        chip.append(
          text('span',dnsRouteLabels[item.routeKind]||item.routeKind,`dns-badge route-${item.routeKind}`),
          text('strong',item.resolver,'dns-resolver-ip'),
          text('span',item.interfaceName,'dns-resolver-iface'),
          text('span',`${item.queries} 次`,'dns-resolver-count')
        );
        if(item.domains?.length)chip.title=`示例域名：${item.domains.join('、')}`;
        resolverBox.append(chip);
      }
    }
    for(const message of r.errors)errors.append(text('p',message));
    const status={active:'已接通',starting:'启动中',awaiting_authorization:'等待授权',cancelled:'已取消',permission_required:'权限不足',unsupported:'不可用',failed:'异常'};
    const diagParts=[
      `网卡抓包：${status[r.packetState]||r.packetState}`,
      `系统事件：${status[r.eventState]||r.eventState}`,
      `明文 DNS ${c.dnsMessages}`,
      `系统事件 ${c.windowsEvents}`
    ];
    if(c.duplicatePackets)diagParts.push(`已去重 ${c.duplicatePackets}`);
    if(c.lostPackets)diagParts.push(`缓冲丢包 ${c.lostPackets}`);
    if(c.lostEvents)diagParts.push(`丢事件 ${c.lostEvents}`);
    if(c.queueDrops)diagParts.push(`队列溢出 ${c.queueDrops}`);
    if(c.parseFailures)diagParts.push(`解析失败 ${c.parseFailures}`);
    if(c.unknownInterfaces)diagParts.push(`未知接口 ${c.unknownInterfaces}`);
    $('dns-coverage').textContent=diagParts.join(' · ');
    const interfaces=$('dns-interfaces');interfaces.replaceChildren();
    for(const i of r.interfaces)interfaces.append(text('p',`${i.name} · ${i.hardware===true?'物理网卡':i.hardware===false?'虚拟接口':'其他组件'} · 接口 ${i.index??'—'}`));
    const head=$('dns-table-head');
    const isSummaryMode=dnsFilter==='summary'||dnsFilter==='overseas'||dnsFilter==='domestic';
    const headers=isSummaryMode?['域名 / 区域','出口路径','解析器 / 网卡','进程','解析结果','次数 / 时间']:['时间 / 来源','查询','阶段 / 结果','进程','解析器','接口 / 方向'];
    if(head){head.replaceChildren();for(const h of headers)head.append(text('th',h));}
    const rows=$('dns-rows');rows.replaceChildren();
    if(isSummaryMode){
      const list=dnsFilter==='overseas'
        ? summaryQueries.filter(q=>q.region==='overseas')
        : dnsFilter==='domestic'
          ? summaryQueries.filter(q=>q.region==='domestic'||q.region==='local'||q.routeKind==='domestic_direct')
          : summaryQueries;
      for(const q of list.slice(0,150)){
        const tr=text('tr','',q.isLeak?'dns-evidence':'');
        const types=(q.queryTypes||[]).map(t=>dnsTypeNames[t]||t).join(' / ')||'—';
        const regionLabel=dnsRegionLabels[q.region]||'';
        const c0=text('td','');
        const titleRow=text('div','','dns-domain-row');
        if(regionLabel)titleRow.append(text('span',regionLabel,`dns-region-tag region-${q.region}`));
        titleRow.append(text('span',q.domain,'dns-domain-name'));
        c0.append(titleRow,text('span',types,'dns-sub-meta'));
        const c1=text('td','');c1.append(text('span',dnsRouteLabels[q.routeKind]||q.routeKind,`dns-badge route-${q.routeKind}`),text('span',q.transport||'','dns-sub-meta'));
        const c2=text('td','');c2.append(text('span',q.resolver||'—','dns-mono'),text('span',q.interfaceName||'—','dns-sub-meta'));
        const c3=text('td',q.process?`${q.process}${q.pid?' · '+q.pid:''}`:q.pid?`PID ${q.pid}`:'—');
        const c4=text('td',(q.answers||[]).join('、')||'—','dns-mono');
        const c5=text('td','');c5.append(text('span',`${q.count} 次`,'dns-mono'),text('span',q.lastSeen?time(q.lastSeen):'','dns-sub-meta'));
        [c0,c1,c2,c3,c4,c5].forEach((cell,i)=>cell.setAttribute('data-label',headers[i]));
        tr.append(c0,c1,c2,c3,c4,c5);rows.append(tr);
      }
      if(!list.length){const tr=text('tr',''),td=text('td',r.running?'等待 DNS 查询…':'暂无符合条件的 DNS 记录');td.colSpan=6;tr.append(td);rows.append(tr);}
      $('dns-rows-status').textContent=`${list.length} 项查询汇总`;
      return;
    }
    const evidence=new Set(a.evidenceIds);
    const source=dnsFilter==='physical'?(r.leakEvidence||r.observations.filter(o=>evidence.has(o.id))):r.observations;
    const filtered=source.filter(o=>dnsFilter!=='encrypted'||dnsEncryptedTransports.has(o.transport));
    for(const o of filtered.slice(-100).reverse()){
      const tr=text('tr','',evidence.has(o.id)?'dns-evidence':'');
      const type=dnsTypeNames[o.queryType]||o.queryType||'—';
      const result=[dnsPhases[o.phase]||o.phase,o.answers.join('、'),o.status!=null&&o.status!==0?`状态 ${o.status}`:'',o.truncated?'截断':''].filter(Boolean).join(' · ');
      const direction={tx:'发送',rx:'接收',unknown:'方向未知'}[o.direction]||'系统事件';
      const values=[`${time(o.observedAt)} · ${o.source==='packet'?'网卡报文':'系统事件'}`,`${o.domain||'DoT/DoQ 加密连接'} · ${type}`,`${o.transport} · ${result}`,o.pid?`${o.process||'进程'} · ${o.pid}`:'—',o.resolver||'—',`${o.interface?.name||'—'} · ${direction}`];
      for(const [i,value] of values.entries()){const cell=text('td',value);cell.setAttribute('data-label',headers[i]);tr.append(cell);}rows.append(tr);
    }
    if(!filtered.length){const tr=text('tr',''),td=text('td',r.running?'等待 DNS 记录…':'暂无符合条件的记录');td.colSpan=6;tr.append(td);rows.append(tr);}
    $('dns-rows-status').textContent=`最近 ${Math.min(filtered.length,100)} / ${filtered.length} 条明细`;
  }
  async function pollDns(){
    clearTimeout(dnsTimer);if(!invoke||dnsBusy)return;
    if(dnsPolling){dnsTimer=setTimeout(pollDns,250);return;}
    dnsPolling=true;
    const revision=dnsRevision;
    try{const result=await invoke('dns_capture_snapshot',{policy:dnsPolicy});if(revision!==dnsRevision)return;dnsCapture=result;dnsError='';renderDns();}
    catch(error){if(revision!==dnsRevision)return;dnsError=`读取采集状态失败：${error?.message||String(error)}`;renderDns();}
    finally{dnsPolling=false;}
    if(revision===dnsRevision&&dnsCapture?.report.running)dnsTimer=setTimeout(pollDns,1000);
  }
  async function controlDns(command){
    if(!invoke||dnsBusy)return;
    if(command==='dns_capture_start')dnsProbedRun=null;
    dnsBusy=true;dnsError='';dnsRevision++;clearTimeout(dnsTimer);renderDns();
    try{dnsCapture=await invoke(command,{seconds:dnsSeconds,policy:dnsPolicy});}
    catch(error){dnsError=`DNS 操作失败：${error?.message||String(error)}`;}
    finally{dnsBusy=false;renderDns();if(dnsCapture?.report.running)dnsTimer=setTimeout(pollDns,500);}
  }
  $('dns-start').onclick=()=>controlDns('dns_capture_start');$('dns-stop').onclick=()=>controlDns('dns_capture_stop');
  if($('dns-probe'))$('dns-probe').onclick=()=>{if(!invoke||dnsBusy||!dnsCapture?.report?.running)return;Promise.resolve(invoke('dns_capture_probe')).then(()=>setTimeout(pollDns,350)).catch(()=>{});};
  for(const [id,policy] of [['dns-policy-proxy','proxy_required'],['dns-policy-observe','observe']])$(id).onclick=()=>{if(dnsBusy)return;dnsPolicy=policy;dnsRevision++;renderDns();if(dnsCapture)return pollDns();};
  for(const [id,seconds] of [['dns-duration-short',30],['dns-duration-long',120]])$(id).onclick=()=>{if(dnsBusy||dnsCapture?.report.running)return;dnsSeconds=seconds;renderDns();};
  for(const key of ['summary','overseas','domestic','physical','encrypted','all'])$(`dns-filter-${key}`) && ($(`dns-filter-${key}`).onclick=()=>{dnsFilter=key;renderDns();});
  renderDns();
  function renderActivePrivacy(){
    const list=$('privacy-active-results');list.replaceChildren();
    if(!activePrivacy){$('privacy-active-title').textContent='尚未执行实际检测';$('privacy-active-status').textContent='点击开始检测，读取 DNS、测试解析并对比公网出口。';return;}
    const done=['dns','system','proxy'].filter(k=>activePrivacy[k]).length;
    const success=['dns','system','proxy'].filter(k=>activePrivacy[k]?.value).length;
    $('privacy-active-title').textContent=done<3?'正在执行实际网络检测':success===3?'本轮检测完成':`检测结束 · ${success}/3 项取得数据`;
    $('privacy-active-status').textContent=`${time(activePrivacy.startedAt)} · ${done}/3 项已结束 · DNS 与公网出口为本轮实测，切换控制器后需重新检测`;
    for(const item of window.PrivacyModel.activeResults(activePrivacy)){
      const card=text('article','',`privacy-check ${item.state}`);card.append(text('h4',item.name),text('strong',item.result),text('p',item.reason));list.append(card);
    }
  }
  async function runPrivacy(){
    if(privacyRunBusy)return;
    if(!invoke){$('privacy-active-status').textContent='实际检测请使用桌面程序。';return;}
    privacyRunBusy=true;$('privacy-run').disabled=true;
    const token=++privacyRunToken,controller=activeController;
    activePrivacy={startedAt:Date.now(),controller:controller||null};renderActivePrivacy();
    try{
      await Promise.all(['dns','system','proxy'].map(async key=>{
        let result;
        try{
          if(key==='proxy'&&!controller)throw new Error('未连接控制器，代理路径未测试');
          const value=await invoke(key==='dns'?'inspect_dns':'egress_address',key==='dns'?{}:{controller:controller||null,viaProxy:key==='proxy'});
          result={value};
        }catch(e){result={error:e?.message||String(e)};}
        if(token!==privacyRunToken)return;
        activePrivacy[key]=result;
        if(key==='dns'&&result.value){homeState.dnsInspection=result.value;renderHome();}
        renderActivePrivacy();
      }));
    }finally{privacyRunBusy=false;$('privacy-run').disabled=false;}
  }
  $('privacy-run').onclick=runPrivacy;
  function privacyReport() {
    return {...window.PrivacyModel.analyze(current,privacyPolicy),
      collection:collecting&&!document.hidden?'running':'paused',
      generatedAt:new Date().toISOString(),environment:privacyEnvironment,activeInspection:activePrivacy,dnsCapture};
  }
  function renderPrivacy() {
    const report=privacyReport(), summary=window.PrivacyModel.summarize(report);
    $('privacy-summary-title').textContent=summary.title;
    $('privacy-summary-detail').textContent=summary.detail;
    const checks=$('privacy-checks');checks.replaceChildren();
    for(const check of summary.checks){
      const card=text('article','',`privacy-check ${check.state}`);
      card.append(text('h4',check.name),text('strong',check.result),text('p',check.reason),text('p',`建议：${check.action}`,'privacy-action'));checks.append(card);
    }
    $('privacy-state').textContent=`${current?time(current.time):'尚无快照'} · ${report.findings.length} 条线索`;
    $('privacy-coverage').textContent=`当前快照 ${report.coverage.connections} 条连接，其中 ${report.coverage.unknownRoutes} 条路由待确认。${report.errors.length?'采集异常：'+report.errors.join('；'):''}`;
    const list=$('privacy-findings');list.replaceChildren();
    for(const finding of report.findings){
      const card=text('article','', 'privacy-finding');
      card.append(text('h4',`${finding.kind==='network'?'网络路径':'连接异常'} · ${finding.title}`),text('p',finding.detail));
      const key=JSON.stringify(['live',finding.evidence.connectionId,finding.title]);
      const details=text('details','');details.open=expandedPrivacy.has(key);
      details.ontoggle=()=>{if(details.open)expandedPrivacy.add(key);else expandedPrivacy.delete(key);};
      details.append(text('summary',`${finding.evidence.process} · PID ${finding.evidence.pid} · 查看连接详情`));
      const fields=text('dl','', 'privacy-facts'), ev=finding.evidence;
      for(const [label,value] of [['应用',ev.process],['进程编号',ev.pid],['源地址',ev.source],['目标地址',ev.destination],['传输协议',ev.protocol],['逻辑路径',ev.route],['命中规则',ev.rule],['采样时间',time(ev.observedAt)]]){
        fields.append(text('dt',label),text('dd',String(value??'未知')));
      }
      details.append(fields);
      card.append(details);list.append(card);
    }
    if(!report.findings.length)list.append(text('p',current?'当前连接快照无异常项。':'等待连接采样。'));
  }
  $('privacy-proxy').onchange=()=>{
    privacyPolicy={...privacyPolicy,expectProxy:$('privacy-proxy').checked};
    if($('settings-expect-proxy'))$('settings-expect-proxy').checked=privacyPolicy.expectProxy;
    renderPrivacy();
  };
  if($('settings-expect-proxy'))$('settings-expect-proxy').onchange=()=>{
    privacyPolicy={...privacyPolicy,expectProxy:$('settings-expect-proxy').checked};
    if($('privacy-proxy'))$('privacy-proxy').checked=privacyPolicy.expectProxy;
    renderPrivacy();
  };
  $('privacy-policy').onsubmit=e=>{
    e.preventDefault();const threshold=Number($('privacy-threshold').value);
    if(!Number.isFinite(threshold)||threshold<1||threshold>1048576)return;
    privacyPolicy={expectProxy:$('privacy-proxy').checked,uploadBytesPerSecond:threshold*1024};renderPrivacy();
  };
  $('privacy-environment').onclick=()=>{
    privacyEnvironment=window.PrivacyModel.environment(window.navigator,window.screen,Intl);
    const list=$('privacy-environment-result');list.replaceChildren();
    for(const item of window.PrivacyModel.describeEnvironment(privacyEnvironment)){
      const card=text('article','', 'privacy-check');card.append(text('h4',item.name),text('strong',item.value),text('p',item.meaning));list.append(card);
    }
    $('privacy-environment-status').textContent='已读取当前 WebView 环境的 4 项基础特征。';
  };
  $('privacy-report').onclick=()=>{const report=privacyReport();$('privacy-report-result').value=JSON.stringify({...report,interpretation:window.PrivacyModel.summarize(report)},null,2);};
  function selectView(next) {
    if(view!==next)$('workspace-content').scrollTop=0;
    view=next;
    for(const name of ['home','graph','monitor','diagnostics','privacy','settings']){
      $(`view-${name}`)?.classList.toggle('active',name===view);
      $(`view-${name}`)?.setAttribute('aria-pressed',String(name===view));
    }
    if($('home-view'))$('home-view').hidden=view!=='home';
    $('privacy-view').hidden=view!=='privacy';$('graph-view').hidden=view!=='graph';$('monitor-view').hidden=view!=='monitor';$('diagnostics').hidden=view!=='diagnostics';$('settings').hidden=view!=='settings';
    renderStatus();
    if(view==='home')renderHome();
    if(view==='graph')render();
    if(view==='diagnostics')window.MonitorTools.discoverDatabases?.();
    if(view==='privacy'&&dnsCapture)pollDns();
  }
  for(const name of ['home','graph','monitor','diagnostics','privacy','settings'])if($(`view-${name}`))$(`view-${name}`).onclick=()=>selectView(name);
  if($('home-check-btn'))$('home-check-btn').onclick=runHomeQualityCheck;
  if($('home-monitor-toggle'))$('home-monitor-toggle').onclick=()=>{
    const loss=window.MonitorTools?.getLossStats?.();
    if(loss?.running)window.MonitorTools?.stopLoss?.();
    else window.MonitorTools?.startLoss?.();
    renderHome();
  };
  for(const [id,ip] of [['home-target-223','223.5.5.5'],['home-target-111','1.1.1.1'],['home-target-888','8.8.8.8']]){
    if($(id))$(id).onclick=()=>{window.MonitorTools?.setLossTarget?.(ip);renderHome();};
  }
  window.MonitorTools?.onChange?.(()=>renderHome());
  function render() {
    const flows=visible();
    if(!flows.some(f=>f.id===selected))selected=flows[0]?.id || null;
    $('flow-count').textContent=String(flows.length);
    $('freeze').disabled=!current;$('timeline').disabled=!frames.length;
    renderStatus();
    $('timeline').max=Math.max(0,frames.length-1);
    $('timeline').value=replay ? Math.max(0,frames.indexOf(replay)) : Math.max(0,frames.length-1);
    $('frame-time').textContent=replay ? time(replay.time) : '现在';
    renderGraph(flows);renderFlows(flows);renderInspector(flows.find(f=>f.id===selected));
  }
  function time(value){return new Date(value).toLocaleTimeString('zh-CN',{hour12:false});}
  function choose(id){if(ignoreClick)return;selected=id;render();}
  function transform(){$('viewport').setAttribute('transform',`translate(${pan.x} ${pan.y}) scale(${scale})`);}
  function retirePulses(key,group,immediate=false){
    if(group.exiting&&!immediate)return;group.exiting=true;
    let remaining=group.dots.length;
    for(const item of group.dots){
      clearTimeout(item.timer);
      const finish=()=>{item.dot.remove();if(--remaining===0&&pulseGroups.get(key)===group)pulseGroups.delete(key);};
      if(immediate){finish();continue;}
      const delay=900-((Date.now()-item.started)%900);
      item.motion.endElementAt?.(delay/1000);item.timer=setTimeout(finish,delay);
    }
  }
  function ensurePulses(key,path,down){
    let group=pulseGroups.get(key);
    if(group){if(group.path!==path){group.path=path;for(const item of group.dots)item.motion.setAttribute('path',path);}return;}
    group={path,dots:[],exiting:false};pulseGroups.set(key,group);
    for(const phase of [0,.45]){
      const dot=svg('circle',{r:down?3.2:2.6,class:`pulse${down?' down':''}`});
      const motion=svg('animateMotion',{dur:'0.9s',begin:'indefinite',repeatCount:'indefinite',path,keyPoints:down?'1;0':'0;1',keyTimes:'0;1',calcMode:'linear',fill:'freeze'});
      dot.append(motion);$('pulses').append(dot);motion.beginElementAt?.(-phase);
      group.dots.push({dot,motion,started:Date.now()-phase*1000,timer:null});
    }
  }
  function renderGraph(flows) {
    // 按画布实际尺寸排布，不随窗口缩小文字。 / Reflow at canvas size instead of scaling text down.
    const width=$('graph').clientWidth || (window.innerWidth<=700?380:860), height=$('graph').clientHeight || 350;
    const compact=width<=560;
    const appWidth=compact?Math.min(148,width*.34):width<720?164:184;
    const targetWidth=compact?Math.min(206,width*.44):width<720?240:270;
    $('network').setAttribute('viewBox',`0 0 ${width} ${height}`);
    $('edges').replaceChildren();$('nodes').replaceChildren();
    const activePulses=new Set(),animate=!frozen&&!replay&&!document.hidden&&animationEnabled&&!rebaseline&&(collecting);
    $('graph-empty').hidden=flows.length>0;
    const groups=M.groupByHost(flows), selectedFlow=flows.find(f=>f.id===selected);
    const displayed=groups.slice(0,6), selectedGroup=groups.find(g=>g.flows.some(f=>f.id===selected));
    if(selectedGroup&&!displayed.includes(selectedGroup))displayed[5]=selectedGroup;
    const drawn=displayed.flatMap(g=>g.flows), names=[...new Set(drawn.map(f=>f.app))];
    const named=names.length>6?names.slice(0,5):names;
    if(names.length>6&&selectedFlow&&!named.includes(selectedFlow.app))named[4]=selectedFlow.app;
    const appKey=name=>named.includes(name)?`app:${name}`:'other-apps';
    const buckets=named.map(name=>({key:appKey(name),label:name,flows:drawn.filter(f=>f.app===name)}));
    if(names.length>6)buckets.push({key:'other-apps',label:'其他应用',flows:drawn.filter(f=>!named.includes(f.app))});
    const leader=list=>list.find(f=>f.id===selected)||list[0];
    if(drawn.length<flows.length)$('flow-count').textContent=`${drawn.length} / ${flows.length}`;
    const nodes=[];
    const slotY=(i,count)=>count<=1?height/2:46+i*(height-86)/(count-1);
    buckets.forEach((bucket,i)=>nodes.push({key:bucket.key,label:bucket.label,sub:`${bucket.flows.length} 条连接`,app:true,
      id:leader(bucket.flows).id,x:appWidth/2+(compact?12:24),y:slotY(i,buckets.length)}));
    displayed.forEach((group,i)=>nodes.push({key:`host:${group.key}`,label:group.label,
      sub:group.flows.length>1?`${group.flows.length} 条连接 · ${new Set(group.flows.map(f=>f.port)).size} 个端口`:`${group.flows[0].protocol} :${group.flows[0].port}`,
      count:group.flows.length,id:leader(group.flows).id,flows:group.flows,
      x:width-targetWidth/2-(compact?12:24),y:slotY(i,displayed.length)}));
    nodes.forEach(n=>{const p=positions.get(n.key);if(p)Object.assign(n,p);});
    for(const group of displayed)for(const bucket of buckets){
      const members=group.flows.filter(f=>appKey(f.app)===bucket.key);if(!members.length)continue;
      const f=leader(members),a=nodes.find(n=>n.key===bucket.key),b=nodes.find(n=>n.key===`host:${group.key}`);
      const aw=appWidth/2,bw=targetWidth/2;
      const bend=Math.min(80,Math.max(0,(b.x-bw-a.x-aw)/2));
      const d=`M ${a.x+aw} ${a.y} C ${a.x+aw+bend} ${a.y},${b.x-bw-bend} ${b.y},${b.x-bw} ${b.y}`;
      const rx=members.reduce((sum,f)=>sum+(f.rx||0),0),tx=members.reduce((sum,f)=>sum+(f.tx||0),0);
      const active=members.some(f=>f.id===selected), warning=(shown()?.events || []).some(e=>members.some(f=>f.id===e.id)&&e.level==='warning');
      const edge=svg('path',{d,class:`edge${active?' selected':''}${warning?' warning':''}`,'stroke-width':Math.min(2.8,1.2+Math.log2(1+rx+tx)/14)});
      const hit=svg('path',{d,class:'edge-hit',tabindex:0,role:'button','aria-label':`${bucket.label} 到 ${group.label}，${members.length} 条连接`});
      hit.onclick=()=>choose(f.id);hit.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();choose(f.id);}};
      hit.onpointerdown=e=>{if(e.button!==0)return;e.preventDefault();e.stopPropagation();drag={id:f.id,start:point(e),origin:{...pan},moved:false};$('network').setPointerCapture(e.pointerId);};
      $('edges').append(edge,hit);
      if(animate){
        const motionPath=`M ${a.x+aw-14} ${a.y} L ${a.x+aw} ${a.y} C ${a.x+aw+bend} ${a.y},${b.x-bw-bend} ${b.y},${b.x-bw} ${b.y} L ${b.x-bw+14} ${b.y}`;
        for(const [rate,down] of [[tx,false],[rx,true]])if(rate>0){
          const key=JSON.stringify([bucket.key,group.key,down]);activePulses.add(key);ensurePulses(key,motionPath,down);
        }
      }
    }
    // 保留在途动画，停止时先抵达节点；采样刷新不重建移动点。 / Keep in-flight dots across samples and drain at the destination.
    for(const [key,group] of pulseGroups)if(!activePulses.has(key))retirePulses(key,group,!animate);
    for(const n of nodes){
      const w=n.app?appWidth:targetWidth,h=46;
      const warning=!n.app&&(shown()?.events || []).some(e=>n.flows.some(f=>f.id===e.id)&&e.level==='warning');
      const group=svg('g',{class:`graph-node${n.app?' app':''}${n.id===selected?' selected':''}${warning?' warning':''}`,transform:`translate(${n.x} ${n.y})`,tabindex:0,role:'button','aria-label':`${n.label}，${n.sub}${warning?'，有异常事件':''}`,'data-key':n.key});
      if(n.count>1)for(const offset of [5,2.5])group.append(svg('rect',{x:-w/2+offset,y:-h/2+offset,width:w,height:h,rx:8,class:'stack-layer'}));
      const rect=svg('rect',{x:-w/2,y:-h/2,width:w,height:h,rx:8,class:'node-card'});
      const accent=svg('rect',{x:-w/2+5,y:-10,width:2.5,height:20,rx:1.25,class:'node-accent'});
      const title=svg('title',{});title.textContent=n.label;
      const label=svg('text',{x:-w/2+14,y:-3});label.textContent=n.label.length>(compact?15:28)?n.label.slice(0,compact?13:26)+'…':n.label;
      const sub=svg('text',{x:-w/2+14,y:13,class:'node-sub'});sub.textContent=n.sub+(warning?' · 超时':'');
      group.append(rect,accent,label,sub,svg('circle',{cx:n.app?w/2:-w/2,cy:0,r:4,class:'port'}),title);
      group.onclick=()=>choose(n.id);group.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();choose(n.id);}};
      group.onpointerdown=e=>{if(e.button!==0)return;e.preventDefault();e.stopPropagation();drag={id:n.id,key:n.key,start:point(e),origin:{x:n.x,y:n.y},moved:false};$('network').setPointerCapture(e.pointerId);};
      $('nodes').append(group);
    }
    transform();
  }
  function point(e){const p=$('network').createSVGPoint();p.x=e.clientX;p.y=e.clientY;return p.matrixTransform($('network').getScreenCTM().inverse());}
  $('network').onpointerdown=e=>{if(e.button!==0)return;e.preventDefault();drag={start:point(e),origin:{...pan},moved:false};$('network').setPointerCapture(e.pointerId);};
  $('network').onpointermove=e=>{
    if(!drag)return;const p=point(e),dx=p.x-drag.start.x,dy=p.y-drag.start.y;
    if(Math.abs(dx)+Math.abs(dy)<4&&!drag.moved)return;drag.moved=true;
    if(drag.key){positions.set(drag.key,{x:drag.origin.x+dx/scale,y:drag.origin.y+dy/scale});renderGraph(visible());}
    else{pan={x:drag.origin.x+dx,y:drag.origin.y+dy};transform();}
  };
  $('network').onpointerup=()=>{const id=drag?.id;ignoreClick=!!drag?.moved;drag=null;if(id&&!ignoreClick)choose(id);setTimeout(()=>ignoreClick=false,0);};
  $('network').onpointercancel=()=>{drag=null;};
  $('zoom-in').onclick=()=>{scale=Math.min(2,scale+.15);transform();};
  $('zoom-out').onclick=()=>{scale=Math.max(.55,scale-.15);transform();};
  $('fit').onclick=()=>{scale=1;pan={x:0,y:0};positions.clear();render();};
  function routeStrip(flow){
    const nodes=M.routePath(flow),strip=text('ol','',`route-strip ${flow.routing}${nodes.length>3?' multi-hop':''}`);
    strip.setAttribute('aria-label',`${flow.routing==='direct'?'直连':'逻辑路由'}：${nodes.map(n=>n.label).join(' → ')}`);
    strip.title=flow.routing==='direct'?'DIRECT · 直连':flow.origin==='system'?'系统连接 · 路由待确认':'逻辑路由；策略组不代表物理服务器跳点';
    for(const node of nodes){
      const step=text('li','',`route-step ${node.kind}`),button=text('button','','route-stop');
      button.type='button';button.title=node.detail;button.setAttribute('aria-label',`${node.label}：${node.detail}`);
      button.append(text('span','','route-dot'),text('span',node.label,'route-label'));button.onclick=()=>choose(flow.id);
      step.append(button);strip.append(step);
    }
    return strip;
  }
  function connectionTrend(flow,samples){
    if(!samples.some(s=>s))return text('span',flow.origin==='system'?'无速率计数':'等待速率样本','trend-empty');
    const chart=svg('svg',{viewBox:'0 0 240 44',class:'connection-trend',role:'img','aria-label':`${flow.app} 到 ${flow.host} 的下载和上传速率趋势`,preserveAspectRatio:'none'});
    const peak=Math.max(0,...samples.flatMap(s=>s || [0,0])),max=Math.max(1,peak);
    const title=svg('title');title.textContent=`最近 ${samples.length} 次快照 · 峰值 ${M.bytes(peak)}/s`;chart.append(title);
    chart.append(svg('path',{d:'M0 40H240',class:'trend-baseline'}));
    for(const [index,direction] of ['down','up'].entries()){
      let connected=false,last=null;
      const path=samples.map((sample,i)=>{
        if(sample?.[index]==null){connected=false;return '';}
        const x=2+(60-samples.length+i)*236/59,y=40-sample[index]/max*34,command=connected?'L':'M';
        connected=true;last={x,y};return `${command}${x},${y}`;
      }).join(' ');
      chart.append(svg('path',{d:path,class:`trend-${direction}`}));
      if(last)chart.append(svg('circle',{cx:last.x,cy:last.y,r:2,class:`trend-tip ${direction}`}));
    }
    return chart;
  }
  const sessionHeaders=Array.from(document.querySelectorAll('.session-table th'),header=>header.textContent);
  function renderFlows(flows){
    const history=M.rateHistory(replay?replayFrames || []:frames);
    $('flow-rows').replaceChildren();
    for(const f of flows.slice(0,200)){
      const tr=text('tr','',f.id===selected?'selected':'');
      const app=document.createElement('td'),target=document.createElement('td'),traffic=text('td','','session-rates'),trend=text('td','','trend-cell');
      for(const [cell,value] of [[app,f.app],[target,f.host]]){
        const button=text('button',value);button.title=value;button.onclick=()=>choose(f.id);cell.append(button);
      }
      const endpoint=text('span',`${f.protocol} · :${f.port}`,'session-endpoint');endpoint.title=f.destination;target.append(endpoint);
      for(const [direction,value] of [['↓',f.rx],['↑',f.tx]]){
        const rate=text('span',`${direction} ${value==null?'—':M.bytes(value)}`);rate.title=`${direction==='↓'?'下载':'上传'} ${value==null?'无计数':M.bytes(value)+'/s'}`;traffic.append(rate);
      }
      const cells=[app,target,traffic,trend];
      cells.forEach((cell,index)=>{cell.dataset.label=sessionHeaders[index];});
      trend.append(connectionTrend(f,history.get(M.flowKey(f)) || []));tr.append(...cells);$('flow-rows').append(tr);
    }
    if(!flows.length){const tr=document.createElement('tr'),td=text('td','没有匹配的会话');td.colSpan=4;tr.append(td);$('flow-rows').append(tr);}
    if(flows.length>200){const tr=document.createElement('tr'),td=text('td',`显示前 200 / ${flows.length} 条，请搜索缩小范围`);td.colSpan=4;tr.append(td);$('flow-rows').append(tr);}
  }
  function renderInspector(f){
    $('selection-title').textContent=f?.host || '选择一条连接';
    $('selection-endpoint').textContent=f?.destination || '';
    $('selection-protocol').textContent=f?.protocol || '—';
    const peers=f?visible().filter(peer=>M.hostKey(peer)===M.hostKey(f)):[];
    $('group-session').replaceChildren();$('group-session').hidden=peers.length<2;
    for(const peer of peers){const option=text('option',`${peer.app} · ${peer.protocol} :${peer.port} · ${peer.source}`);option.value=peer.id;$('group-session').append(option);}
    $('group-session').value=f?.id || '';
    const body=$('inspect-content');
    const inspectKey=`${f?.id || ''}|${tab}`;
    const prevScroll=inspectKey===lastInspectKey?(body.scrollTop || 0):0;
    lastInspectKey=inspectKey;
    const nodes=[];
    if(!f){nodes.push(text('p','点击节点、连线或会话，查看对应证据。','empty-detail'));}
    else if(tab==='raw'){
      nodes.push(text('pre',JSON.stringify(f.system?{controller:f.raw,system:f.system}:f.processOwner?{controller:f.raw,process_owner:f.processOwner}:f.raw,null,2)));
      const details=document.createElement('details'),summary=text('summary','数据包');details.append(summary,text('p','当前来源为连接快照，尚未接入原始数据包。','empty-detail'));nodes.push(details);
    }
    else if(tab==='events'){
      const events=[{time:f.origin==='system'?shown().time:new Date(f.start).getTime(),text:f.origin==='system'?'系统 TCP 连接快照':'控制器报告的会话开始',evidence:`连接 ${f.id}`},...(shown()?.events || []).filter(e=>e.id===f.id)];
      for(const e of events){const item=text('div','',`event-item ${e.level || ''}`);item.append(text('time',Number.isFinite(e.time)?time(e.time):'时间未知'),text('p',e.text),text('p',e.evidence,'endpoint'));nodes.push(item);}
    }
    else {
      nodes.push(routeStrip(f));
      const dl=document.createElement('dl');
      for(const [k,v] of Object.entries({'来源':f.origin==='system'?'系统 TCP（路由待确认）':f.routing==='direct'?'Mihomo · DIRECT 直连':'Mihomo · '+(f.routing==='proxy'?'代理转发':'路由待确认'),'应用':`${f.app} · PID ${f.pid}`,'进程依据':f.processSource==='windows-pid'?'Windows 进程表（PID）':f.processSource==='windows-socket'?'Windows 连接所属进程':f.processSource==='windows-udp'?'Windows UDP 端点所属进程':f.processSource==='controller'?'控制器报告':'未提供 PID 或无法唯一匹配系统连接','源地址':f.source,'匹配规则':f.rule,'观测状态':f.origin==='system'?'已建立 · 无逐连接流量计数':f.rx===null?'等待连续快照':f.rx+f.tx>0?'有流量增量':'本次无流量增量'})){dl.append(text('dt',k),text('dd',v));}nodes.push(dl);
      const pair=text('div','','traffic-pair');
      for(const [label,v] of [['累计下载',f.download],['累计上传',f.upload]]){const cell=document.createElement('div');cell.append(text('span',label),text('strong',M.bytes(v)));pair.append(cell);}nodes.push(pair);
    }
    // 同一会话定时刷新时保持检查器滚动位置，切换会话或标签后回顶。 / Keep scrollTop on live refresh for the same flow/tab; reset on switch.
    body.replaceChildren(...nodes);
    body.scrollTop=prevScroll;
  }
  function applyPerformanceMode(code, persist = true) {
    perfMode = Q?.resolvePerformanceMode?.(code) || perfMode;
    if (persist) writeStorage('wiesler.performanceMode', perfMode.code);
    window.MonitorTools?.setPerformanceMode?.(perfMode);
    animationEnabled = userPulsePreference && perfMode.allowPulseAnimation;
    if ($('flow-animation')) $('flow-animation').checked = animationEnabled;
    document.body?.classList.toggle('flow-animation', animationEnabled);
    for (const key of ['eco', 'balanced', 'high']) {
      $(`perf-mode-${key}`)?.setAttribute('aria-pressed', String(perfMode.code === key));
    }
    if ($('perf-mode-desc')) $('perf-mode-desc').textContent = perfMode.summary;
    if ($('settings-summary-perf')) $('settings-summary-perf').textContent = perfMode.label;
    if ($('home-perf-badge')) $('home-perf-badge').textContent = perfMode.label;
    renderGraph(visible());
  }
  function systemColorSchemeLabel() {
    const dark = Boolean(window.matchMedia?.('(prefers-color-scheme: dark)')?.matches);
    return dark ? '当前深色' : '当前浅色';
  }
  function applyThemeMode(code, persist = true) {
    themeMode = Q?.resolveThemeMode?.(code) || { code: 'system', label: '跟随系统' };
    if (persist) {
      // 遵循系统时移除持久化键，固定浅色/深色时写入字面值。 / Clear stored override for system; persist literal scheme otherwise.
      writeStorage('wiesler.theme', themeMode.code === 'system' ? null : themeMode.code);
    }
    const meta = document.querySelector?.('meta[name="color-scheme"]');
    if (meta) meta.content = themeMode.code === 'system' ? 'light dark' : themeMode.code;
    if ($('theme-mode-system')) $('theme-mode-system').textContent = `跟随系统（${systemColorSchemeLabel()}）`;
    for (const key of ['system', 'light', 'dark']) {
      $(`theme-mode-${key}`)?.setAttribute('aria-pressed', String(themeMode.code === key));
    }
    if ($('settings-summary-theme')) $('settings-summary-theme').textContent = themeMode.label;
  }
  function selectSettingsTab(next) {
    settingsTab = next;
    for (const key of ['controller', 'monitoring', 'appearance', 'about']) {
      const btn = $(`settings-tab-${key}`);
      const panel = $(`settings-panel-${key}`);
      const active = key === settingsTab;
      if (btn) {
        btn.setAttribute('aria-selected', String(active));
        btn.tabIndex = active ? 0 : -1;
      }
      if (panel) panel.hidden = !active;
    }
  }
  const settingsTabs = ['controller', 'monitoring', 'appearance', 'about'];
  settingsTabs.forEach((key, index) => {
    const btn = $(`settings-tab-${key}`);
    if (!btn) return;
    btn.onclick = () => selectSettingsTab(key);
    btn.onkeydown = event => {
      const count = settingsTabs.length;
      const next = { ArrowLeft: (index + count - 1) % count, ArrowRight: (index + 1) % count, Home: 0, End: count - 1 }[event.key];
      if (next === undefined) return;
      event.preventDefault();
      const target = $(`settings-tab-${settingsTabs[next]}`);
      target?.focus?.();
      selectSettingsTab(settingsTabs[next]);
    };
  });
  for (const key of ['eco', 'balanced', 'high']) {
    if ($(`perf-mode-${key}`)) $(`perf-mode-${key}`).onclick = () => applyPerformanceMode(key, true);
  }
  for (const key of ['system', 'light', 'dark']) {
    if ($(`theme-mode-${key}`)) $(`theme-mode-${key}`).onclick = () => applyThemeMode(key, true);
  }
  window.addEventListener('storage', event => {
    if (event.key === 'wiesler.theme') applyThemeMode(event.newValue || 'system', false);
    if (event.key === 'wiesler.performanceMode') applyPerformanceMode(event.newValue || 'balanced', false);
  });
  window.matchMedia?.('(prefers-color-scheme: dark)')?.addEventListener?.('change', () => {
    // 系统明暗切换时仅更新提示文字，绝不清除用户手动固定的主题。 / Refresh system hint only; never erase an explicit user pin.
    if ($('theme-mode-system')) $('theme-mode-system').textContent = `跟随系统（${systemColorSchemeLabel()}）`;
  });
  applyPerformanceMode(perfMode.code, false);
  applyThemeMode(themeMode.code, false);
  selectSettingsTab('controller');
  $('flow-animation').onchange=()=>{
    userPulsePreference = $('flow-animation').checked;
    animationEnabled = userPulsePreference;
    document.body?.classList.toggle('flow-animation',animationEnabled);
    renderGraph(visible());
  };
  $('group-session').onchange=()=>choose($('group-session').value);
  const inspectorTabs=Array.from(document.querySelectorAll('.inspector-tabs [role="tab"]'));
  inspectorTabs.forEach((button,index)=>{
    button.onclick=()=>{
      tab=button.dataset.tab;
      for(const item of inspectorTabs){
        item.setAttribute('aria-selected',String(item===button));
        item.tabIndex=item===button?0:-1;
      }
      $('inspect-content').setAttribute('aria-labelledby',button.id);
      renderInspector(visible().find(f=>f.id===selected));
    };
    button.onkeydown=event=>{
      const count=inspectorTabs.length;
      const next={ArrowLeft:(index+count-1)%count,ArrowRight:(index+1)%count,Home:0,End:count-1}[event.key];
      if(next===undefined)return;
      event.preventDefault();
      inspectorTabs[next].focus();
      inspectorTabs[next].click();
    };
  });
  $('flow-search').oninput=render;$('flow-protocol').onchange=render;$('flow-source').onchange=render;
  function unfreeze(){frozen=false;replay=null;replayFrames=null;$('freeze').textContent='冻结';render();}
  $('freeze').onclick=()=>{if(!current)return;if(frozen||replay){unfreeze();return;}frozen=true;replay=current;replayFrames=frames.slice();$('freeze').textContent='继续';render();};
  $('timeline').oninput=()=>{replay=frames[Number($('timeline').value)] || null;replayFrames=frames.slice(0,Number($('timeline').value)+1);frozen=false;$('freeze').textContent='继续';render();};
  $('now').onclick=unfreeze;
  function reset(){
    privacyRunToken++;activePrivacy=null;renderActivePrivacy();
    homeState.checkToken++;homeState.checkBusy=false;homeState.systemEgress=null;homeState.systemEgressError=null;homeState.proxyEgress=null;homeState.proxyEgressError=null;
    if($('home-check-btn')){$('home-check-btn').disabled=false;$('home-check-btn').textContent='一键体检';}
    for(const [key,group] of pulseGroups)retirePulses(key,group,true);
    generation++;egressGeneration++;clearTimeout(timer);clearTimeout(systemTimer);collecting=!!invoke;
    for(const route of ['system','proxy']){$(`egress-${route}-ip`).textContent='—';$(`egress-${route}-status`).textContent='尚未探测';$(`egress-${route}-location`).textContent='地理位置未知';}
    $('egress-refresh').disabled=false;
    discovering=false;discoveryMessage='';rebaseline=false;activeController='';
    window.MonitorTools?.setControllerHealth({connected:false,healthy:false,error:null});
    frames=[];current=null;controllerFrame=null;systemCurrent=null;systemError=null;replay=null;replayFrames=null;frozen=false;positions.clear();
    $('freeze').textContent='冻结';$('snapshot-count').textContent='0 次快照';
    renderMonitor();render();
    if(collecting)pollSystem(generation);
  }
  async function pollSystem(token){
    if(token!==generation||!collecting||document.hidden)return;
    if(systemBusy){systemTimer=setTimeout(()=>pollSystem(token),250);return;}
    systemBusy=true;
    try{
      const data=await invoke('system_snapshot');if(token!==generation||document.hidden)return;
      systemCurrent=data;systemError=null;
    }catch(error){if(token!==generation||document.hidden)return;systemCurrent=null;systemError=error?.message||String(error);}
    finally{
      systemBusy=false;
      if(token===generation&&collecting&&!document.hidden){
        if(!activeController||!controllerFrame||controllerFrame.error)accept(M.mergeSources(controllerFrame,systemCurrent,systemError));
        renderStatus();systemTimer=setTimeout(()=>pollSystem(token),perfMode.systemTickMs || 3000);
      }
    }
  }
  async function poll(token){
    if(token!==generation||!collecting||document.hidden)return;
    if(liveBusy){timer=setTimeout(()=>poll(token),250);return;}
    if(!invoke){$('source-state').textContent='实时连接请使用桌面程序';return;}
    liveBusy=true;
    try{const raw=await invoke('snapshot',{controller:activeController});if(token!==generation||document.hidden)return;window.MonitorTools.appendRequestLog(raw.http_status,null);window.MonitorTools.setControllerHealth({connected:true,healthy:true,error:null});controllerFrame=M.frame(raw,rebaseline?null:controllerFrame,raw.observed_at);accept(M.mergeSources(controllerFrame,systemCurrent,systemError));rebaseline=false;}
    catch(e){if(token!==generation||document.hidden)return;const next={time:Date.now(),flows:[],events:[],source:'live',error:e?.message||String(e)};window.MonitorTools.appendRequestLog(e?.http_status,next.error);window.MonitorTools.setControllerHealth({connected:true,healthy:false,error:next.error});controllerFrame=next;accept(M.mergeSources(controllerFrame,systemCurrent,systemError));}
    finally{liveBusy=false;if(token===generation&&collecting&&!document.hidden)timer=setTimeout(()=>poll(token),perfMode.activeTickMs || 1000);}
  }
  async function refreshEgress(){
    if(!invoke)return;
    const token=++egressGeneration,controller=activeController;
    $('egress-refresh').disabled=true;
    await Promise.all(['system','proxy'].map(async route=>{
      $(`egress-${route}-ip`).textContent='—';$(`egress-${route}-location`).textContent='地理位置未知';
      if(route==='proxy'&&!controller){$('egress-proxy-status').textContent='未连接控制器';homeState.proxyEgress=null;homeState.proxyEgressError=null;return;}
      $(`egress-${route}-status`).textContent='探测中…';
      try{
        const result=await invoke('egress_address',{controller:controller || null,viaProxy:route==='proxy'});
        if(token!==egressGeneration)return;
        if(route==='system'){homeState.systemEgress=result;homeState.systemEgressError=null;}
        else{homeState.proxyEgress=result;homeState.proxyEgressError=null;}
        $(`egress-${route}-ip`).textContent=result.ip;
        $(`egress-${route}-ip`).title=`${result.provider || '出口探测'}${result.proxy?' · '+result.proxy:''}`;
        $(`egress-${route}-status`).textContent=time(result.observed_at);
        const geo=result.location;let country=geo?.country;
        if(geo?.country_code){try{country=new Intl.DisplayNames(['zh-CN'],{type:'region'}).of(geo.country_code);}catch(_){}}
        const place=[...new Set([country,geo?.region,geo?.city].filter(Boolean))].join(' · ');
        $(`egress-${route}-location`).textContent=[place || '地理位置未知',geo?.isp].filter(Boolean).join(' · ');
        $(`egress-${route}-location`).title=result.location_error || 'IP 地址库归属地 · ipwho.is';
        renderHome();
      }catch(error){
        if(token===egressGeneration){
          const msg=error?.message||String(error);
          if(route==='system'){homeState.systemEgress=null;homeState.systemEgressError=msg;}
          else{homeState.proxyEgress=null;homeState.proxyEgressError=msg;}
          $(`egress-${route}-status`).textContent=msg;
          renderHome();
        }
      }
    }));
    if(token===egressGeneration)$('egress-refresh').disabled=false;
  }
  $('egress-refresh').onclick=refreshEgress;
  function connectController(address) {
    reset();
    if(!invoke){discoveryMessage='实时连接请使用桌面程序';renderStatus();return;}
    activeController=address;$('controller').value=address;collecting=true;renderStatus();poll(generation);refreshEgress();
  }
  async function discover() {
    reset();const token=generation;
    $('controller-choice').hidden=true;$('connect-discovered').hidden=true;
    if(!invoke){discoveryMessage='实时连接请使用桌面程序';renderStatus();return;}
    discovering=true;renderStatus();
    try {
      // 多次切换共用在途扫描，旧结果不能覆盖新的来源。 / Share scans and discard superseded results.
      if(!scanPromise)scanPromise=invoke('discover_controllers').finally(()=>{scanPromise=null;});
      const result=await scanPromise;if(token!==generation)return;
      discovering=false;
      const ready=result.controllers.filter(c=>c.ready);
      const choices=ready.length?ready:result.controllers;
      $('controller-choice').replaceChildren();
      for(const c of choices){const option=text('option',`${c.controller} · ${c.ready?c.version:c.configured?'需要鉴权':'待验证 · 需要鉴权'}`);option.value=c.controller;$('controller-choice').append(option);}
      $('controller-choice').hidden=choices.length===0;$('connect-discovered').hidden=choices.length===0;
      if(ready.length===1){$('controller-choice').value=ready[0].controller;connectController(ready[0].controller);return;}
      discoveryMessage=ready.length>1?`发现 ${ready.length} 个控制器，请选择连接`:choices.length?'发现需要鉴权的本机服务，请选择控制器':result.warning || '未发现可用控制器，请启动代理客户端后重新扫描';
      renderStatus();refreshEgress();
    } catch(error){if(token!==generation)return;discovering=false;discoveryMessage=`扫描失败：${error?.message||String(error)}`;renderStatus();refreshEgress();}
  }
  $('discover').onclick=discover;
  $('connect-discovered').onclick=()=>{const address=$('controller-choice').value;if(address)connectController(address);};
  $('controller-form').onsubmit=e=>{e.preventDefault();connectController($('controller').value.trim());};
  $('capture-toggle').onclick=()=>{
    if(!invoke)return;
    collecting=!collecting;generation++;clearTimeout(timer);clearTimeout(systemTimer);discovering=false;
    // 暂停后的首帧只建基线，保留旧快照供检查。 / Resume without averaging across the pause.
    if(collecting){if(!activeController){discover();return;}rebaseline=true;pollSystem(generation);poll(generation);}
    renderStatus();renderGraph(visible());
  };
  window.addEventListener('resize',()=>{positions.clear();renderGraph(visible());});
  document.addEventListener('visibilitychange',()=>{clearTimeout(timer);clearTimeout(systemTimer);generation++;discovering=false;renderStatus();renderGraph(visible());if(document.hidden)return;if(collecting){if(!activeController){discover();return;}rebaseline=true;pollSystem(generation);poll(generation);}});
  window.addEventListener('online', renderHome);
  window.addEventListener('offline', renderHome);
  renderHome();
  discover();
})();
