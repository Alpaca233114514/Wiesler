(() => {
  'use strict';
  const $ = id => document.getElementById(id), M = window.StudioModel;
  const svgNS = 'http://www.w3.org/2000/svg';
  const invoke = window.__TAURI__?.core?.invoke;
  let mode = 'live', frames = [], current = null, selected = null, tab = 'overview';
  let frozen = false, replay = null, timer, generation = 0, demoTick = 24, liveBusy = false;
  let collecting = false, activeController = '', view = 'graph', rebaseline = false;
  let systemCurrent = null, systemError = null, systemBusy = false, systemTimer, controllerFrame = null;
  let discovering = false, scanPromise = null, discoveryMessage = '';
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
    $('system-state').textContent=mode==='demo'?'':systemError?`系统连接：${systemError}`:systemCurrent?`系统 TCP · ${systemCurrent.sockets.length} 条`:'正在读取系统连接…';
    $('discover').disabled=discovering;
    $('controller-address').textContent=activeController;
    $('source-state').hidden=false;$('statusbar').hidden=false;
    $('capture-state').textContent = replay ? `${frozen?'已冻结':'回放中'} · ${mode==='demo'||collecting&&!document.hidden?'采集继续':'采集暂停'}` : '';
    $('capture-toggle').disabled=mode!=='live'||!invoke;
    $('capture-toggle').textContent=collecting?'暂停采集':'恢复采集';
  }
  function renderMonitor() {
    const f=current, valid=f&&!f.error;
    $('monitor-count').textContent=f?String(f.flows.length):'—';
    $('monitor-routing').textContent=f?`直连 ${f.flows.filter(c=>c.routing==='direct').length} · 转发 ${f.flows.filter(c=>c.routing==='proxy').length} · 系统 ${f.flows.filter(c=>c.origin==='system').length}`:'等待采样';
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
    if(view==='graph')render();
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
  function renderGraph(flows) {
    const compact=window.innerWidth<=700;
    // 按画布实际尺寸排布，不随窗口缩小文字。 / Reflow at canvas size instead of scaling text down.
    const width=$('graph').clientWidth || (compact?380:860), height=$('graph').clientHeight || 350;
    const appWidth=compact?124:width<720?164:184, targetWidth=compact?152:width<720?240:270;
    $('network').setAttribute('viewBox',`0 0 ${width} ${height}`);
    $('edges').replaceChildren();$('nodes').replaceChildren();
    $('graph-empty').hidden=flows.length>0;
    const displayed=flows.slice(0,6);
    if(selected&&!displayed.some(f=>f.id===selected)&&flows.some(f=>f.id===selected))displayed[5]=flows.find(f=>f.id===selected);
    const apps=[...new Set(displayed.map(f=>f.app))];
    if(flows.length>6)$('flow-count').textContent=`6 / ${flows.length}`;
    const nodes=[];
    apps.forEach((app,i)=>nodes.push({key:`app:${app}`,label:app,sub:`${flows.filter(f=>f.app===app).length} 条会话`,app:true,
      id:displayed.find(f=>f.app===app).id,x:appWidth/2+(compact?12:24),y:65+i*(height-135)/Math.max(1,apps.length-1)}));
    displayed.forEach((f,i)=>nodes.push({key:f.id,label:f.host,sub:`${f.protocol} :${f.port}`,id:f.id,
      x:width-targetWidth/2-(compact?12:24),y:42+i*(height-95)/Math.max(1,displayed.length-1)}));
    nodes.forEach(n=>{const p=positions.get(n.key);if(p)Object.assign(n,p);});
    for(const f of displayed){
      const a=nodes.find(n=>n.key===`app:${f.app}`),b=nodes.find(n=>n.key===f.id);
      const aw=appWidth/2,bw=targetWidth/2;
      const d=`M ${a.x+aw} ${a.y} C ${a.x+aw+80} ${a.y},${b.x-bw-80} ${b.y},${b.x-bw} ${b.y}`;
      const active=f.id===selected, warning=(shown()?.events || []).some(e=>e.id===f.id&&e.level==='warning');
      const edge=svg('path',{d,class:`edge${active?' selected':''}${warning?' warning':''}`,'stroke-width':Math.min(6,1.5+Math.log2(1+(f.rx||0)+(f.tx||0))/5)});
      const hit=svg('path',{d,class:'edge-hit',tabindex:0,role:'button','aria-label':`${f.app} 到 ${f.host}`});
      hit.onclick=()=>choose(f.id);hit.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();choose(f.id);}};
      hit.onpointerdown=e=>{if(e.button!==0)return;e.preventDefault();e.stopPropagation();drag={id:f.id,start:point(e),origin:{...pan},moved:false};$('network').setPointerCapture(e.pointerId);};
      $('edges').append(edge,hit);
      if(!frozen&&!replay&&!document.hidden&&!window.matchMedia('(prefers-reduced-motion: reduce)').matches){
        for(const [rate,down] of [[f.tx,false],[f.rx,true]])if(rate>0){
          const dot=svg('circle',{r:down?4:3,class:`pulse${down?' down':''}`});
          const motion=svg('animateMotion',{dur:'0.9s',repeatCount:'1',path:d,keyPoints:down?'1;0':'0;1',keyTimes:'0;1',calcMode:'linear',fill:'freeze'});
          dot.append(motion);$('edges').append(dot);
        }
      }
    }
    for(const n of nodes){
      const w=n.app?appWidth:targetWidth,h=54;
      const warning=!n.app&&(shown()?.events || []).some(e=>e.id===n.id&&e.level==='warning');
      const group=svg('g',{class:`graph-node${n.app?' app':''}${n.id===selected?' selected':''}${warning?' warning':''}`,transform:`translate(${n.x} ${n.y})`,tabindex:0,role:'button','aria-label':`${n.label}，${n.sub}${warning?'，有异常事件':''}`,'data-key':n.key});
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
  function renderFlows(flows){
    $('flow-rows').replaceChildren();
    for(const f of flows.slice(0,200)){
      const tr=text('tr','',f.id===selected?'selected':'');
      [f.app,f.host,f.protocol,f.rx==null?'—':M.bytes(f.rx),f.tx==null?'—':M.bytes(f.tx),f.route].forEach((v,i)=>{
        const td=document.createElement('td');
        if(i<2){const b=text('button',v);b.onclick=()=>choose(f.id);td.append(b);}else td.textContent=v;
        tr.append(td);
      });$('flow-rows').append(tr);
    }
    if(!flows.length){const tr=document.createElement('tr'),td=text('td','没有匹配的会话');td.colSpan=6;tr.append(td);$('flow-rows').append(tr);}
    if(flows.length>200){const tr=document.createElement('tr'),td=text('td',`显示前 200 / ${flows.length} 条，请搜索缩小范围`);td.colSpan=6;tr.append(td);$('flow-rows').append(tr);}
  }
  function renderInspector(f){
    $('selection-title').textContent=f?.host || '选择一条连接';
    $('selection-endpoint').textContent=f?.destination || '';
    $('selection-protocol').textContent=f?.protocol || '—';
    const body=$('inspect-content');body.replaceChildren();
    if(!f){body.append(text('p','点击节点、连线或会话，查看对应证据。','empty-detail'));return;}
    if(tab==='raw'){
      body.append(text('pre',JSON.stringify(f.system?{controller:f.raw,system:f.system}:f.raw,null,2)));
      const details=document.createElement('details'),summary=text('summary','数据包');details.append(summary,text('p','当前来源为连接快照，尚未接入原始数据包。','empty-detail'));body.append(details);return;
    }
    if(tab==='events'){
      const events=[{time:f.origin==='system'?shown().time:new Date(f.start).getTime(),text:f.origin==='system'?'系统 TCP 连接快照':'控制器报告的会话开始',evidence:`连接 ${f.id}`},...(shown()?.events || []).filter(e=>e.id===f.id)];
      for(const e of events){const item=text('div','',`event-item ${e.level || ''}`);item.append(text('time',Number.isFinite(e.time)?time(e.time):'时间未知'),text('p',e.text),text('p',e.evidence,'endpoint'));body.append(item);}return;
    }
    const dl=document.createElement('dl');
    for(const [k,v] of Object.entries({'来源':f.origin==='system'?'系统 TCP（路由待确认）':f.routing==='direct'?'Mihomo · DIRECT 直连':'Mihomo · '+(f.routing==='proxy'?'代理转发':'路由待确认'),'应用':`${f.app} · PID ${f.pid}`,'源地址':f.source,'出口':f.route,'匹配规则':f.rule,'观测状态':f.origin==='system'?'已建立 · 无逐连接流量计数':f.rx===null?'等待连续快照':f.rx+f.tx>0?'有流量增量':'本次无流量增量'})){dl.append(text('dt',k),text('dd',v));}body.append(dl);
    const pair=text('div','','traffic-pair');
    for(const [label,v] of [['累计下载',f.download],['累计上传',f.upload]]){const cell=document.createElement('div');cell.append(text('span',label),text('strong',M.bytes(v)));pair.append(cell);}body.append(pair);
  }
  document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{tab=b.dataset.tab;document.querySelectorAll('[data-tab]').forEach(n=>{n.classList.toggle('active',n===b);n.setAttribute('aria-selected',String(n===b));});renderInspector(visible().find(f=>f.id===selected));});
  $('flow-search').oninput=render;$('flow-protocol').onchange=render;$('flow-source').onchange=render;
  function unfreeze(){frozen=false;replay=null;$('freeze').textContent='冻结';render();}
  $('freeze').onclick=()=>{if(!current)return;if(frozen||replay){unfreeze();return;}frozen=true;replay=current;$('freeze').textContent='继续';render();};
  $('timeline').oninput=()=>{replay=frames[Number($('timeline').value)] || null;frozen=false;$('freeze').textContent='继续';render();};
  $('now').onclick=unfreeze;
  function reset(next){
    generation++;clearTimeout(timer);clearTimeout(systemTimer);collecting=next==='live'&&!!invoke;
    discovering=false;discoveryMessage='';rebaseline=false;activeController='';mode=next;
    frames=[];current=null;controllerFrame=null;systemCurrent=null;systemError=null;replay=null;frozen=false;positions.clear();
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
  function connectController(address) {
    reset('live');
    if(!invoke){discoveryMessage='实时连接请使用桌面程序';renderStatus();return;}
    activeController=address;$('controller').value=address;collecting=true;renderStatus();poll(generation);
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
      renderStatus();
    } catch(error){if(token!==generation)return;discovering=false;discoveryMessage=`扫描失败：${error?.message||String(error)}`;renderStatus();}
  }
  $('discover').onclick=discover;
  $('connect-discovered').onclick=()=>{const address=$('controller-choice').value;if(address)connectController(address);};
  $('demo').onclick=startDemo;
  $('live').onclick=()=>{if(mode!=='live')discover();};
  $('controller-form').onsubmit=e=>{e.preventDefault();connectController($('controller').value.trim());};
  $('capture-toggle').onclick=()=>{
    if(mode!=='live'||!invoke)return;
    collecting=!collecting;generation++;clearTimeout(timer);clearTimeout(systemTimer);
    // 暂停后的首帧只建基线，保留旧快照供检查。 / Resume without averaging across the pause.
    if(collecting){rebaseline=true;pollSystem(generation);if(activeController)poll(generation);}
    renderStatus();
  };
  window.addEventListener('resize',()=>{positions.clear();renderGraph(visible());});
  document.addEventListener('visibilitychange',()=>{clearTimeout(timer);clearTimeout(systemTimer);generation++;renderStatus();if(document.hidden)return;if(mode==='demo')demoStep();else if(collecting){rebaseline=true;pollSystem(generation);if(activeController)poll(generation);}});
  discover();
})();
