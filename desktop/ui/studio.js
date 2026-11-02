(() => {
  'use strict';
  const $ = id => document.getElementById(id), M = window.StudioModel;
  const svgNS = 'http://www.w3.org/2000/svg';
  const invoke = window.__TAURI__?.core?.invoke;
  let mode = 'live', frames = [], current = null, selected = null, tab = 'overview';
  let frozen = false, replay = null, timer, generation = 0, demoTick = 24, liveBusy = false;
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
    $('source-state').textContent = mode==='demo' ? '演示数据' : discovering ? '正在扫描本机控制器…' : current?.error ? `读取失败：${current.error}` : !collecting ? (current ? '已暂停 · 保留最后快照' : discoveryMessage || '尚未连接') : document.hidden ? '窗口隐藏 · 采集暂停' : activeController ? (controllerFrame ? '实时 · Mihomo' : '正在连接代理控制器…') : discoveryMessage || '本机连接采集中';
    $('system-state').textContent=mode==='demo'?'':!invoke?'系统连接需使用桌面程序':systemError?`系统连接：${systemError}`:systemCurrent?`系统 TCP · ${systemCurrent.sockets.length} 条`:'正在读取系统连接…';
    $('egress-panel').hidden=mode==='demo'||!['graph','monitor'].includes(view);
    $('discover').disabled=discovering;
    $('controller-address').textContent=activeController;
    $('source-state').hidden=false;$('statusbar').hidden=false;
    $('capture-state').textContent = replay ? `${frozen?'已冻结':'回放中'} · ${mode==='demo'||collecting&&!document.hidden?'采集继续':'采集暂停'}` : '';
    $('capture-toggle').disabled=mode!=='live'||!invoke;
    $('capture-toggle').textContent=collecting?'暂停采集':'恢复采集';
  }
  function renderMonitor() {
    const f=current, valid=f&&!f.error&&f.download!=null, observed=f&&(mode==='demo'||systemCurrent||controllerFrame&&!controllerFrame.error);
    $('monitor-count').textContent=observed?String(f.flows.length):'—';
    $('monitor-routing').textContent=observed?`直连 ${f.flows.filter(c=>c.routing==='direct').length} · 转发 ${f.flows.filter(c=>c.routing==='proxy').length} · 系统 ${f.flows.filter(c=>c.origin==='system').length}`:'等待采样';
    $('monitor-time').textContent=f?`${time(f.time)} · ${mode==='demo'?'演示':f.error?'采集异常':'最近采样'}`:'尚无采样';
    $('monitor-rx').textContent=valid?f.rx==null?'等待差分':M.bytes(f.rx):'—';
    $('monitor-tx').textContent=valid?f.tx==null?'等待差分':M.bytes(f.tx):'—';
    $('monitor-download').textContent=valid?`累计 ${M.bytes(f.download)}`:'等待有效快照';
    $('monitor-upload').textContent=valid?`累计 ${M.bytes(f.upload)}`:'等待有效快照';
    const max=Math.max(1,...frames.flatMap(f=>[f.rx||0,f.tx||0]));
    for(const direction of ['rx','tx']){
      let connected=false;
      const path=frames.map((f,i)=>{if(f[direction]==null){connected=false;return '';}
        const command=connected?'L':'M';connected=true;
        return `${command}${(60-frames.length+i)*800/59},${150-f[direction]/max*140}`;}).join(' ');
      $(`trend-${direction}`).setAttribute('d',path);
    }
    $('trend-scale').textContent=`峰值 ${M.bytes(max)}/s`;
  }
  function selectView(next) {
    view=next;
    for(const name of ['graph','monitor','diagnostics','settings']){
      $(`view-${name}`).classList.toggle('active',name===view);
      $(`view-${name}`).setAttribute('aria-pressed',String(name===view));
    }
    $('graph-view').hidden=view!=='graph';$('monitor-view').hidden=view!=='monitor';$('diagnostics').hidden=view!=='diagnostics';$('settings').hidden=view!=='settings';
    renderStatus();if(view==='graph')render();
  }
  for(const name of ['graph','monitor','diagnostics','settings'])$(`view-${name}`).onclick=()=>selectView(name);
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
      const dot=svg('circle',{r:down?4.5:3.5,class:`pulse${down?' down':''}`});
      const motion=svg('animateMotion',{dur:'0.9s',begin:'indefinite',repeatCount:'indefinite',path,keyPoints:down?'1;0':'0;1',keyTimes:'0;1',calcMode:'linear',fill:'freeze'});
      dot.append(motion);$('pulses').append(dot);motion.beginElementAt?.(-phase);
      group.dots.push({dot,motion,started:Date.now()-phase*1000,timer:null});
    }
  }
  function renderGraph(flows) {
    const compact=window.innerWidth<=700;
    // 按画布实际尺寸排布，不随窗口缩小文字。 / Reflow at canvas size instead of scaling text down.
    const width=$('graph').clientWidth || (compact?380:860), height=$('graph').clientHeight || 350;
    const appWidth=compact?124:width<720?164:184, targetWidth=compact?152:width<720?240:270;
    $('network').setAttribute('viewBox',`0 0 ${width} ${height}`);
    $('edges').replaceChildren();$('nodes').replaceChildren();
    const activePulses=new Set(),animate=!frozen&&!replay&&!document.hidden&&animationEnabled&&!rebaseline&&(mode==='demo'||collecting);
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
    buckets.forEach((bucket,i)=>nodes.push({key:bucket.key,label:bucket.label,sub:`${bucket.flows.length} 条连接`,app:true,
      id:leader(bucket.flows).id,x:appWidth/2+(compact?12:24),y:65+i*(height-135)/Math.max(1,buckets.length-1)}));
    displayed.forEach((group,i)=>nodes.push({key:`host:${group.key}`,label:group.label,
      sub:group.flows.length>1?`${group.flows.length} 条连接 · ${new Set(group.flows.map(f=>f.port)).size} 个端口`:`${group.flows[0].protocol} :${group.flows[0].port}`,
      count:group.flows.length,id:leader(group.flows).id,flows:group.flows,
      x:width-targetWidth/2-(compact?12:24),y:42+i*(height-95)/Math.max(1,displayed.length-1)}));
    nodes.forEach(n=>{const p=positions.get(n.key);if(p)Object.assign(n,p);});
    for(const group of displayed)for(const bucket of buckets){
      const members=group.flows.filter(f=>appKey(f.app)===bucket.key);if(!members.length)continue;
      const f=leader(members),a=nodes.find(n=>n.key===bucket.key),b=nodes.find(n=>n.key===`host:${group.key}`);
      const aw=appWidth/2,bw=targetWidth/2;
      const d=`M ${a.x+aw} ${a.y} C ${a.x+aw+80} ${a.y},${b.x-bw-80} ${b.y},${b.x-bw} ${b.y}`;
      const rx=members.reduce((sum,f)=>sum+(f.rx||0),0),tx=members.reduce((sum,f)=>sum+(f.tx||0),0);
      const active=members.some(f=>f.id===selected), warning=(shown()?.events || []).some(e=>members.some(f=>f.id===e.id)&&e.level==='warning');
      const edge=svg('path',{d,class:`edge${active?' selected':''}${warning?' warning':''}`,'stroke-width':Math.min(6,1.5+Math.log2(1+rx+tx)/5)});
      const hit=svg('path',{d,class:'edge-hit',tabindex:0,role:'button','aria-label':`${bucket.label} 到 ${group.label}，${members.length} 条连接`});
      hit.onclick=()=>choose(f.id);hit.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();choose(f.id);}};
      hit.onpointerdown=e=>{if(e.button!==0)return;e.preventDefault();e.stopPropagation();drag={id:f.id,start:point(e),origin:{...pan},moved:false};$('network').setPointerCapture(e.pointerId);};
      $('edges').append(edge,hit);
      if(animate){
        const motionPath=`M ${a.x+aw-14} ${a.y} L ${a.x+aw} ${a.y} C ${a.x+aw+80} ${a.y},${b.x-bw-80} ${b.y},${b.x-bw} ${b.y} L ${b.x-bw+14} ${b.y}`;
        for(const [rate,down] of [[tx,false],[rx,true]])if(rate>0){
          const key=JSON.stringify([bucket.key,group.key,down]);activePulses.add(key);ensurePulses(key,motionPath,down);
        }
      }
    }
    // 保留在途动画，停止时先抵达节点；采样刷新不重建移动点。 / Keep in-flight dots across samples and drain at the destination.
    for(const [key,group] of pulseGroups)if(!activePulses.has(key))retirePulses(key,group,!animate);
    for(const n of nodes){
      const w=n.app?appWidth:targetWidth,h=54;
      const warning=!n.app&&(shown()?.events || []).some(e=>n.flows.some(f=>f.id===e.id)&&e.level==='warning');
      const group=svg('g',{class:`graph-node${n.app?' app':''}${n.id===selected?' selected':''}${warning?' warning':''}`,transform:`translate(${n.x} ${n.y})`,tabindex:0,role:'button','aria-label':`${n.label}，${n.sub}${warning?'，有异常事件':''}`,'data-key':n.key});
      if(n.count>1)for(const offset of [8,4])group.append(svg('rect',{x:-w/2+offset,y:-h/2+offset,width:w,height:h,rx:10,class:'stack-layer'}));
      const rect=svg('rect',{x:-w/2,y:-h/2,width:w,height:h,rx:10});
      const label=svg('text',{x:-w/2+14,y:-4});label.textContent=n.label.length>(compact?15:28)?n.label.slice(0,compact?13:26)+'…':n.label;
      const sub=svg('text',{x:-w/2+14,y:17,class:'node-sub'});sub.textContent=n.sub+(warning?' · 超时':'');
      group.append(rect,label,sub,svg('circle',{cx:n.app?w/2:-w/2,cy:0,r:5,class:'port'}));
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
    const nodes=M.routePath(flow),strip=text('ol','',`route-strip ${flow.routing}`);
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
      trend.append(connectionTrend(f,history.get(M.flowKey(f)) || []));tr.append(app,target,traffic,trend);$('flow-rows').append(tr);
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
  document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{tab=b.dataset.tab;document.querySelectorAll('[data-tab]').forEach(n=>{n.classList.toggle('active',n===b);n.setAttribute('aria-selected',String(n===b));});renderInspector(visible().find(f=>f.id===selected));});
  $('flow-search').oninput=render;$('flow-protocol').onchange=render;$('flow-source').onchange=render;
  function unfreeze(){frozen=false;replay=null;replayFrames=null;$('freeze').textContent='冻结';render();}
  $('freeze').onclick=()=>{if(!current)return;if(frozen||replay){unfreeze();return;}frozen=true;replay=current;replayFrames=frames.slice();$('freeze').textContent='继续';render();};
  $('timeline').oninput=()=>{replay=frames[Number($('timeline').value)] || null;replayFrames=frames.slice(0,Number($('timeline').value)+1);frozen=false;$('freeze').textContent='继续';render();};
  $('now').onclick=unfreeze;
  function reset(next){
    for(const [key,group] of pulseGroups)retirePulses(key,group,true);
    generation++;egressGeneration++;clearTimeout(timer);clearTimeout(systemTimer);collecting=next==='live'&&!!invoke;
    for(const route of ['system','proxy']){$(`egress-${route}-ip`).textContent='—';$(`egress-${route}-status`).textContent='尚未探测';$(`egress-${route}-location`).textContent='地理位置未知';}
    $('egress-refresh').disabled=false;
    discovering=false;discoveryMessage='';rebaseline=false;activeController='';mode=next;
    frames=[];current=null;controllerFrame=null;systemCurrent=null;systemError=null;replay=null;replayFrames=null;frozen=false;positions.clear();
    $('freeze').textContent='冻结';$('snapshot-count').textContent='0 次快照';
    $('demo').classList.toggle('active',next==='demo');$('live').classList.toggle('active',next==='live');
    $('controller-tools').hidden=next!=='live';renderMonitor();render();
    if(collecting)pollSystem(generation);
  }
  async function pollSystem(token){
    if(token!==generation||mode!=='live'||!collecting||document.hidden)return;
    if(systemBusy){systemTimer=setTimeout(()=>pollSystem(token),250);return;}
    systemBusy=true;
    try{
      const data=await invoke('system_snapshot');if(token!==generation||document.hidden)return;
      systemCurrent=data;systemError=null;
    }catch(error){if(token!==generation||document.hidden)return;systemCurrent=null;systemError=error?.message||String(error);}
    finally{
      systemBusy=false;
      if(token===generation&&mode==='live'&&collecting&&!document.hidden){
        if(!activeController||!controllerFrame||controllerFrame.error)accept(M.mergeSources(controllerFrame,systemCurrent,systemError));
        renderStatus();systemTimer=setTimeout(()=>pollSystem(token),3000);
      }
    }
  }
  function demoStep(){if(mode!=='demo'||document.hidden)return;const t=Date.UTC(2026,10,2,2,18,0)+demoTick*1000;accept(M.frame(M.demo(demoTick++),current,t,'demo'));timer=setTimeout(demoStep,1000);}
  function startDemo(){reset('demo');demoTick=0;for(let i=0;i<24;i++)accept(M.frame(M.demo(demoTick),current,Date.UTC(2026,10,2,2,18,demoTick++),'demo'));demoStep();}
  async function poll(token){
    if(token!==generation||mode!=='live'||!collecting||document.hidden)return;
    if(liveBusy){timer=setTimeout(()=>poll(token),250);return;}
    if(!invoke){$('source-state').textContent='实时连接请使用桌面程序';return;}
    liveBusy=true;
    try{const raw=await invoke('snapshot',{controller:activeController});if(token!==generation||document.hidden)return;window.MonitorTools.appendRequestLog(raw.http_status,null);controllerFrame=M.frame(raw,rebaseline?null:controllerFrame,raw.observed_at);accept(M.mergeSources(controllerFrame,systemCurrent,systemError));rebaseline=false;}
    catch(e){if(token!==generation||document.hidden)return;const next={time:Date.now(),flows:[],events:[],source:'live',error:e?.message||String(e)};window.MonitorTools.appendRequestLog(e?.http_status,next.error);controllerFrame=next;accept(M.mergeSources(controllerFrame,systemCurrent,systemError));}
    finally{liveBusy=false;if(token===generation&&mode==='live'&&collecting&&!document.hidden)timer=setTimeout(()=>poll(token),1000);}
  }
  async function refreshEgress(){
    if(!invoke||mode!=='live')return;
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
    reset('live');
    if(!invoke){discoveryMessage='实时连接请使用桌面程序';renderStatus();return;}
    activeController=address;$('controller').value=address;collecting=true;renderStatus();poll(generation);refreshEgress();
  }
  async function discover() {
    reset('live');const token=generation;
    $('controller-choice').hidden=true;$('connect-discovered').hidden=true;
    if(!invoke){discoveryMessage='实时连接请使用桌面程序，也可切换演示数据';renderStatus();return;}
    discovering=true;renderStatus();
    try {
      // 多次切换共用在途扫描，旧结果不能覆盖新的来源。 / Share scans and discard superseded results.
      if(!scanPromise)scanPromise=invoke('discover_controllers').finally(()=>{scanPromise=null;});
      const result=await scanPromise;if(token!==generation||mode!=='live')return;
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
  $('demo').onclick=startDemo;
  $('live').onclick=()=>{if(mode!=='live')discover();};
  $('controller-form').onsubmit=e=>{e.preventDefault();connectController($('controller').value.trim());};
  $('capture-toggle').onclick=()=>{
    if(mode!=='live'||!invoke)return;
    collecting=!collecting;generation++;clearTimeout(timer);clearTimeout(systemTimer);discovering=false;
    // 暂停后的首帧只建基线，保留旧快照供检查。 / Resume without averaging across the pause.
    if(collecting){if(!activeController){discover();return;}rebaseline=true;pollSystem(generation);poll(generation);}
    renderStatus();renderGraph(visible());
  };
  window.addEventListener('resize',()=>{positions.clear();renderGraph(visible());});
  document.addEventListener('visibilitychange',()=>{clearTimeout(timer);clearTimeout(systemTimer);generation++;discovering=false;renderStatus();renderGraph(visible());if(document.hidden)return;if(mode==='demo')demoStep();else if(collecting){if(!activeController){discover();return;}rebaseline=true;pollSystem(generation);poll(generation);}});
  discover();
})();
