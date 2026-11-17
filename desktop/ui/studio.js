(() => {
  'use strict';
  const $ = id => document.getElementById(id), M = window.StudioModel;
  const svgNS = 'http://www.w3.org/2000/svg';
  const invoke = window.__TAURI__?.core?.invoke;
  let frames = [], current = null, selected = null, tab = 'overview';
  let frozen = false, replay = null, timer, generation = 0, liveBusy = false;
  let collecting = false, activeController = '', view = 'graph', rebaseline = false;
  let systemCurrent = null, systemError = null, systemBusy = false, systemTimer, controllerFrame = null;
  let egressGeneration = 0, replayFrames = null, animationEnabled = true;
  let discovering = false, scanPromise = null, discoveryMessage = '';
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
    renderMonitor();renderStatus();
    if(!frozen && !replay) render();
  }
  function renderStatus() {
    $('source-state').textContent = discovering ? '正在扫描本机控制器…' : current?.error ? `读取失败：${current.error}` : !collecting ? (current ? '已暂停 · 保留最后快照' : discoveryMessage || '尚未连接') : document.hidden ? '窗口隐藏 · 采集暂停' : activeController ? (controllerFrame ? '实时 · Mihomo' : '正在连接代理控制器…') : discoveryMessage || '本机连接采集中';
    $('source-state').setAttribute('data-tone', current?.error ? 'danger' : (!collecting || discovering || document.hidden || !invoke) ? 'warning' : 'live');
    $('system-state').textContent=!invoke?'系统连接需使用桌面程序':systemError?`系统连接：${systemError}`:systemCurrent?`系统 TCP · ${systemCurrent.sockets.length} 条`:'正在读取系统连接…';
    $('egress-panel').hidden=!['graph','monitor'].includes(view);
    $('discover').disabled=discovering;
    $('controller-address').textContent=activeController;
    $('source-state').hidden=false;$('statusbar').hidden=false;
    $('capture-state').textContent = replay ? `${frozen?'已冻结':'回放中'} · ${collecting&&!document.hidden?'采集继续':'采集暂停'}` : '';
    $('capture-toggle').disabled=!invoke;
    $('capture-toggle').textContent=collecting?'暂停采集':'恢复采集';
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
  let dnsCapture = null, dnsBusy = false, dnsPolling = false, dnsTimer = null, dnsRevision = 0, dnsSeconds = 30, dnsPolicy = 'proxy_required', dnsFilter = 'all', dnsError = '';
  const dnsPhases = {started:'开始查询',completed:'解析完成',send:'发送查询',receive:'接收响应',network_query:'网络查询',result:'解析结果',query:'查询报文',response:'响应报文',encrypted_query:'系统加密查询',encrypted_candidate:'加密端口线索'};
  function renderDns(){
    const r=dnsCapture?.report, a=dnsCapture?.assessment;
    $('dns-start').disabled=dnsBusy||!!r?.running||!invoke;$('dns-stop').disabled=dnsBusy||!r?.running;
    $('dns-policy-proxy').setAttribute('aria-pressed',String(dnsPolicy==='proxy_required'));
    $('dns-policy-observe').setAttribute('aria-pressed',String(dnsPolicy==='observe'));
    $('dns-policy-proxy').disabled=$('dns-policy-observe').disabled=dnsBusy;
    $('dns-duration-short').setAttribute('aria-pressed',String(dnsSeconds===30));$('dns-duration-long').setAttribute('aria-pressed',String(dnsSeconds===120));
    $('dns-duration-short').disabled=$('dns-duration-long').disabled=dnsBusy||!!r?.running;
    for(const key of ['all','physical','encrypted'])$(`dns-filter-${key}`).setAttribute('aria-pressed',String(key===dnsFilter));
    const errors=$('dns-errors');errors.replaceChildren();if(dnsError)errors.append(text('p',dnsError));
    if(!r){
      if(dnsError)$('dns-state').textContent='DNS 操作未完成';
      $('dns-detail').textContent=`当前条件：${dnsPolicy==='proxy_required'?'DNS 应经代理 / 隧道':'仅观察分流'}。点击开始后正常使用应用，采集 DNS 查询与网卡报文。`;
      return;
    }
    const starting=r.packetState==='starting'||r.eventState==='starting';
    $('dns-state').textContent=`${r.running?(r.packetState==='awaiting_authorization'?'等待 Windows 授权':starting?'正在启动本地采集':'持续采集中'):'采集已结束'} · ${time(r.startedAt)}${r.stoppedAt?' — '+time(r.stoppedAt):''}`;
    const pending=dnsCapture.policy!==dnsPolicy;
    const condition=dnsPolicy==='proxy_required'?'DNS 应经代理 / 隧道':'仅观察分流';
    $('dns-verdict').textContent=pending?'正在按当前条件重新判断':starting?'正在连接 Windows 采集接口':a.title;
    $('dns-verdict').className=!pending&&a.status==='leak'?'dns-leak':'';
    $('dns-detail').textContent=`当前条件：${condition}。${pending?'正在使用已有采集记录重算，无需重新采集。':starting?'正在自动发现网卡并订阅 DNS 事件。':a.detail}${!pending&&['incomplete','no_sample','unavailable'].includes(a.status)?' 切换条件不会补足缺失的采集证据。':''}`;
    const c=r.coverage;
    $('dns-counts').textContent=`${c.windowsEvents} 条系统 DNS 事件 · ${c.dnsMessages} 条明文 DNS 报文 · ${a.physicalQueries} 条物理出口查询 · ${a.encryptedEvents} 条系统加密查询`;
    for(const message of r.errors)errors.append(text('p',message));
    const status={active:'已接通',starting:'启动中',awaiting_authorization:'等待授权',cancelled:'已取消',permission_required:'权限不足',unsupported:'不可用',failed:'异常'};
    $('dns-coverage').textContent=`网卡捕获：${status[r.packetState]||r.packetState}；系统 DNS 事件：${status[r.eventState]||r.eventState}。丢失报文 ${c.lostPackets}，丢失事件 ${c.lostEvents}，队列丢失 ${c.queueDrops}，解析失败 ${c.parseFailures}，未重组 IP 分片 ${c.fragments}，不支持的帧 ${c.unsupportedPackets}，不完整 TCP ${c.incompleteTcp}，未知网卡 ${c.unknownInterfaces}，超出保留上限 ${c.retainedRowsDropped}。Windows DoH/DoT 可见；应用自带加密 DNS 的正文不保证可见。`;
    const interfaces=$('dns-interfaces');interfaces.replaceChildren();
    for(const i of r.interfaces)interfaces.append(text('p',`${i.name} · ${i.hardware===true?'物理网卡':i.hardware===false?'虚拟接口':'类型未知'} · 接口 ${i.index??'未知'} · 组件 ${i.componentId}`));
    const evidence=new Set(a.evidenceIds);
    const source=dnsFilter==='physical'?(r.leakEvidence||r.observations.filter(o=>evidence.has(o.id))):r.observations;
    const filtered=source.filter(o=>dnsFilter!=='encrypted'||['DoH','DoT','TLS/853 candidate','QUIC/853 candidate'].includes(o.transport));
    const rows=$('dns-rows');rows.replaceChildren();
    for(const o of filtered.slice(-100).reverse()){
      const tr=text('tr','',evidence.has(o.id)?'dns-evidence':'');
      const type={1:'A',2:'NS',5:'CNAME',12:'PTR',15:'MX',16:'TXT',28:'AAAA',65:'HTTPS'}[o.queryType]||o.queryType||'—';
      const result=[dnsPhases[o.phase]||o.phase,o.answers.join('、'),o.status!=null?`状态 ${o.status}`:'',o.truncated?'响应截断':''].filter(Boolean).join(' · ');
      const direction={tx:'发送',rx:'接收',unknown:'方向未知'}[o.direction]||'事件记录';
      const values=[`${time(o.observedAt)} · ${o.source==='packet'?'网卡报文':'Windows 事件'}`,`${o.domain||'加密正文不可见'} · ${type}`,`${o.transport} · ${result}`,o.pid?`${o.process||'进程名不可用'} · PID ${o.pid}`:'未关联进程',o.resolver||'未提供',`${o.interface?.name||'未提供实际接口'} · ${direction}`];
      for(const [i,value] of values.entries()){const cell=text('td',value);cell.setAttribute('data-label',['时间 / 来源','查询','阶段 / 结果','进程','解析器','接口 / 方向'][i]);tr.append(cell);}rows.append(tr);
    }
    if(!filtered.length){const tr=text('tr',''),td=text('td',r.running?'等待符合条件的 DNS 记录':'本轮没有符合条件的记录');td.colSpan=6;tr.append(td);rows.append(tr);}
    $('dns-rows-status').textContent=`显示最近 ${Math.min(filtered.length,100)} / ${filtered.length} 条；完整记录可生成 JSON 报告`;
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
    dnsBusy=true;dnsError='';dnsRevision++;clearTimeout(dnsTimer);renderDns();
    try{dnsCapture=await invoke(command,{seconds:dnsSeconds,policy:dnsPolicy});}
    catch(error){dnsError=`DNS 操作失败：${error?.message||String(error)}`;}
    finally{dnsBusy=false;renderDns();if(dnsCapture?.report.running)dnsTimer=setTimeout(pollDns,500);}
  }
  $('dns-start').onclick=()=>controlDns('dns_capture_start');$('dns-stop').onclick=()=>controlDns('dns_capture_stop');
  for(const [id,policy] of [['dns-policy-proxy','proxy_required'],['dns-policy-observe','observe']])$(id).onclick=()=>{if(dnsBusy)return;dnsPolicy=policy;dnsRevision++;renderDns();if(dnsCapture)return pollDns();};
  for(const [id,seconds] of [['dns-duration-short',30],['dns-duration-long',120]])$(id).onclick=()=>{if(dnsBusy||dnsCapture?.report.running)return;dnsSeconds=seconds;renderDns();};
  for(const key of ['all','physical','encrypted'])$(`dns-filter-${key}`).onclick=()=>{dnsFilter=key;renderDns();};
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
        activePrivacy[key]=result;renderActivePrivacy();
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
    $('privacy-coverage').textContent=`连接快照包含 ${report.coverage.connections} 条连接，${report.coverage.unknownRoutes} 条路由待确认。DNS 查询正文和网卡证据请查看上方本地检查；连接快照不含这些信息。${report.errors.length?'采集异常：'+report.errors.join('；'):''}`;
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
    if(!report.findings.length)list.append(text('p',current?'当前样本未触发检查条件；不表示已排除泄漏。':'等待连接采样。'));
  }
  $('privacy-proxy').onchange=()=>{
    privacyPolicy={...privacyPolicy,expectProxy:$('privacy-proxy').checked};renderPrivacy();
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
    $('privacy-environment-status').textContent='已读取当前页面可暴露的 4 类信息；尚未检测指纹唯一性或跨会话追踪。';
  };
  $('privacy-report').onclick=()=>{const report=privacyReport();$('privacy-report-result').value=JSON.stringify({...report,interpretation:window.PrivacyModel.summarize(report)},null,2);};
  function selectView(next) {
    if(view!==next)$('workspace-content').scrollTop=0;
    view=next;
    for(const name of ['graph','monitor','diagnostics','privacy','settings']){
      $(`view-${name}`).classList.toggle('active',name===view);
      $(`view-${name}`).setAttribute('aria-pressed',String(name===view));
    }
    $('privacy-view').hidden=view!=='privacy';$('graph-view').hidden=view!=='graph';$('monitor-view').hidden=view!=='monitor';$('diagnostics').hidden=view!=='diagnostics';$('settings').hidden=view!=='settings';
    renderStatus();if(view==='graph')render();
    if(view==='privacy'&&dnsCapture)pollDns();
  }
  for(const name of ['graph','monitor','diagnostics','privacy','settings'])$(`view-${name}`).onclick=()=>selectView(name);
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
    const body=$('inspect-content');body.replaceChildren();
    if(!f){body.append(text('p','点击节点、连线或会话，查看对应证据。','empty-detail'));return;}
    if(tab==='raw'){
      body.append(text('pre',JSON.stringify(f.system?{controller:f.raw,system:f.system}:f.processOwner?{controller:f.raw,process_owner:f.processOwner}:f.raw,null,2)));
      const details=document.createElement('details'),summary=text('summary','数据包');details.append(summary,text('p','当前来源为连接快照，尚未接入原始数据包。','empty-detail'));body.append(details);return;
    }
    if(tab==='events'){
      const events=[{time:f.origin==='system'?shown().time:new Date(f.start).getTime(),text:f.origin==='system'?'系统 TCP 连接快照':'控制器报告的会话开始',evidence:`连接 ${f.id}`},...(shown()?.events || []).filter(e=>e.id===f.id)];
      for(const e of events){const item=text('div','',`event-item ${e.level || ''}`);item.append(text('time',Number.isFinite(e.time)?time(e.time):'时间未知'),text('p',e.text),text('p',e.evidence,'endpoint'));body.append(item);}return;
    }
    body.append(routeStrip(f));
    const dl=document.createElement('dl');
    for(const [k,v] of Object.entries({'来源':f.origin==='system'?'系统 TCP（路由待确认）':f.routing==='direct'?'Mihomo · DIRECT 直连':'Mihomo · '+(f.routing==='proxy'?'代理转发':'路由待确认'),'应用':`${f.app} · PID ${f.pid}`,'进程依据':f.processSource==='windows-pid'?'Windows 进程表（PID）':f.processSource==='windows-socket'?'Windows 连接所属进程':f.processSource==='windows-udp'?'Windows UDP 端点所属进程':f.processSource==='controller'?'控制器报告':'未提供 PID 或无法唯一匹配系统连接','源地址':f.source,'匹配规则':f.rule,'观测状态':f.origin==='system'?'已建立 · 无逐连接流量计数':f.rx===null?'等待连续快照':f.rx+f.tx>0?'有流量增量':'本次无流量增量'})){dl.append(text('dt',k),text('dd',v));}body.append(dl);
    const pair=text('div','','traffic-pair');
    for(const [label,v] of [['累计下载',f.download],['累计上传',f.upload]]){const cell=document.createElement('div');cell.append(text('span',label),text('strong',M.bytes(v)));pair.append(cell);}body.append(pair);
  }
  $('flow-animation').checked=true;document.body?.classList.add('flow-animation');
  $('flow-animation').onchange=()=>{animationEnabled=$('flow-animation').checked;document.body?.classList.toggle('flow-animation',animationEnabled);renderGraph(visible());};
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
    for(const [key,group] of pulseGroups)retirePulses(key,group,true);
    generation++;egressGeneration++;clearTimeout(timer);clearTimeout(systemTimer);collecting=!!invoke;
    for(const route of ['system','proxy']){$(`egress-${route}-ip`).textContent='—';$(`egress-${route}-status`).textContent='尚未探测';$(`egress-${route}-location`).textContent='地理位置未知';}
    $('egress-refresh').disabled=false;
    discovering=false;discoveryMessage='';rebaseline=false;activeController='';
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
        renderStatus();systemTimer=setTimeout(()=>pollSystem(token),3000);
      }
    }
  }
  async function poll(token){
    if(token!==generation||!collecting||document.hidden)return;
    if(liveBusy){timer=setTimeout(()=>poll(token),250);return;}
    if(!invoke){$('source-state').textContent='实时连接请使用桌面程序';return;}
    liveBusy=true;
    try{const raw=await invoke('snapshot',{controller:activeController});if(token!==generation||document.hidden)return;window.MonitorTools.appendRequestLog(raw.http_status,null);controllerFrame=M.frame(raw,rebaseline?null:controllerFrame,raw.observed_at);accept(M.mergeSources(controllerFrame,systemCurrent,systemError));rebaseline=false;}
    catch(e){if(token!==generation||document.hidden)return;const next={time:Date.now(),flows:[],events:[],source:'live',error:e?.message||String(e)};window.MonitorTools.appendRequestLog(e?.http_status,next.error);controllerFrame=next;accept(M.mergeSources(controllerFrame,systemCurrent,systemError));}
    finally{liveBusy=false;if(token===generation&&collecting&&!document.hidden)timer=setTimeout(()=>poll(token),1000);}
  }
  async function refreshEgress(){
    if(!invoke)return;
    const token=++egressGeneration,controller=activeController;
    $('egress-refresh').disabled=true;
    await Promise.all(['system','proxy'].map(async route=>{
      $(`egress-${route}-ip`).textContent='—';$(`egress-${route}-location`).textContent='地理位置未知';
      if(route==='proxy'&&!controller){$('egress-proxy-status').textContent='未连接控制器';return;}
      $(`egress-${route}-status`).textContent='探测中…';
      try{
        const result=await invoke('egress_address',{controller:controller || null,viaProxy:route==='proxy'});
        if(token!==egressGeneration)return;
        $(`egress-${route}-ip`).textContent=result.ip;
        $(`egress-${route}-ip`).title=`${result.provider || '出口探测'}${result.proxy?' · '+result.proxy:''}`;
        $(`egress-${route}-status`).textContent=time(result.observed_at);
        const geo=result.location;let country=geo?.country;
        if(geo?.country_code){try{country=new Intl.DisplayNames(['zh-CN'],{type:'region'}).of(geo.country_code);}catch(_){}}
        const place=[...new Set([country,geo?.region,geo?.city].filter(Boolean))].join(' · ');
        $(`egress-${route}-location`).textContent=[place || '地理位置未知',geo?.isp].filter(Boolean).join(' · ');
        $(`egress-${route}-location`).title=result.location_error || 'IP 地址库归属地 · ipwho.is';
      }catch(error){if(token===egressGeneration)$(`egress-${route}-status`).textContent=error?.message||String(error);}
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
  discover();
})();
