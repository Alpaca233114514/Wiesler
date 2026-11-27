(function(root) {
  'use strict';
  const siteDict = root.SiteDictionary || (typeof require === 'function' ? (() => { try { return require('./site-dictionary.js'); } catch (_) { return null; } })() : null);
  const resolveSite = host => siteDict?.resolveSite ? siteDict.resolveSite(host) : null;
  const number = n => Number.isFinite(Number(n)) && Number(n) >= 0 ? Number(n) : 0;
  const endpoint = (host, port) => `${String(host).includes(':') ? `[${host}]` : host}:${port || '?'}`;
  const processName = name => typeof name==='string'&&!['','unknown','未知进程'].includes(name.trim().toLowerCase())?name.trim():null;
  const bytes = n => {
    if (n == null || !Number.isFinite(n)) return '—';
    const i = Math.min(3, Math.floor(Math.log(Math.max(1, n)) / Math.log(1024)));
    return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${['B', 'KB', 'MB', 'GB'][i]}`;
  };
  function normalize(raw) {
    const m = raw.metadata || {}, value = (a,b) => m[a] ?? m[b];
    const host = m.host || value('sniff_host','sniffHost') || value('destination_ip','destinationIP') || '未知目标';
    const site = resolveSite(host);
    const process=processName(m.process),pid=Number(m.pid)>0?Number(m.pid):'未知';
    return {id:String(raw.id), start:raw.start || '', app:process || (pid==='未知'?'进程待识别':`PID ${pid}`), pid,
      processSource:process?'controller':null,
      host, site, siteName:site?.name || null, siteUrl:site?.url || null, siteCategory:site?.category || null,
      destination:endpoint(value('destination_ip','destinationIP') || host,value('destination_port','destinationPort')),
      source:endpoint(value('source_ip','sourceIP') || '未知',value('source_port','sourcePort')),
      port:value('destination_port','destinationPort') || '?', protocol:(m.network || '未知').toUpperCase(),
      route:(raw.chains || []).join(' → ') || '未知', rule:raw.rule || '未知',
      origin:'mihomo', routing:raw.chains?.[0]==='DIRECT'?'direct':raw.chains?.length?'proxy':'unknown',
      download:number(raw.download), upload:number(raw.upload), raw};
  }
  function systemFlow(socket) {
    const site = resolveSite(socket.remote_ip);
    return {id:`system:${socket.pid}:${socket.process_started_at || ''}:${socket.local_ip}:${socket.local_port}:${socket.remote_ip}:${socket.remote_port}`,
      start:'', app:processName(socket.process) || `PID ${socket.pid}`, processSource:processName(socket.process)?'windows-socket':null, pid:socket.pid, host:socket.remote_ip,
      site, siteName:site?.name || null, siteUrl:site?.url || null, siteCategory:site?.category || null,
      destination:endpoint(socket.remote_ip,socket.remote_port),source:endpoint(socket.local_ip,socket.local_port),
      port:socket.remote_port,protocol:'TCP',route:'系统连接 · 路由待确认',rule:'—',origin:'system',routing:'unknown',
      download:null,upload:null,rx:null,tx:null,raw:socket};
  }
  const hostKey = flow => flow.host.toLowerCase().replace(/\.$/,'');
  const siteGroupKey = flow => flow.site ? `site:${flow.site.id}` : `host:${hostKey(flow)}`;
  const flowKey = flow => JSON.stringify([flow.id,flow.start]);
  function matchesQuery(flow, rawQuery) {
    const q = String(rawQuery || '').trim().toLowerCase();
    if (!q) return true;
    const fields = [flow.app, flow.host, flow.destination, flow.source, flow.route, flow.rule,
      flow.siteName, flow.siteUrl, flow.siteCategory, ...(flow.site?.aliases || [])];
    return fields.some(v => v && String(v).toLowerCase().includes(q));
  }
  function rateHistory(frames) {
    const history=new Map();
    frames.forEach((frame,index)=>{
      for(const flow of frame.flows || []){
        if(flow.rx==null&&flow.tx==null)continue;
        const key=flowKey(flow);
        if(!history.has(key))history.set(key,Array(frames.length).fill(null));
        history.get(key)[index]=[flow.rx,flow.tx];
      }
    });
    return history;
  }
  function groupByHost(flows) {
    const groups=new Map();
    for(const flow of flows){
      const key=hostKey(flow);
      if(!groups.has(key))groups.set(key,{key,label:flow.host,site:null,hosts:[flow.host],flows:[]});
      groups.get(key).flows.push(flow);
    }
    return [...groups.values()];
  }
  function groupBySite(flows) {
    const groups=new Map();
    for(const flow of flows){
      const key=siteGroupKey(flow);
      if(!groups.has(key)){
        groups.set(key,{key,label:flow.site ? flow.site.name : flow.host,site:flow.site || null,hosts:[],hostSet:new Set(),flows:[],order:groups.size});
      }
      const group=groups.get(key);
      const hk=hostKey(flow);
      if(!group.hostSet.has(hk)){group.hostSet.add(hk);group.hosts.push(flow.host);}
      group.flows.push(flow);
    }
    const isRawIp = label => /^(\d{1,3}(\.\d{1,3}){3}|\[?[0-9a-fA-F:]+\]?)$/.test(String(label || '').trim());
    // 按网站视图优先展示已识别网站与域名节点，避免单连接裸 IP 挤占前 6 个图谱槽位。
    // Prioritize mapped sites and domain hosts over raw IP literals in site view so the 6 graph slots stay informative.
    return [...groups.values()]
      .sort((a, b) => {
        const rank = g => (g.site ? 2 : isRawIp(g.label) ? 0 : 1);
        return (rank(b) - rank(a)) || (b.flows.length - a.flows.length) || (a.order - b.order);
      })
      .map(({hostSet, order, ...rest}) => rest);
  }
  function routePath(flow) {
    const nodes=[{kind:'computer',label:'电脑',detail:`${flow.app} · ${flow.source}`}];
    if(flow.routing==='proxy'){
      // 控制器从出口向外追加策略组，展示时反向展开。 / Reverse the controller's leaf-first logical chain.
      for(const name of [...(flow.raw.chains || [])].reverse())nodes.push({kind:'route',label:name,detail:`逻辑路由节点 · ${name}`});
    }else if(flow.routing!=='direct')nodes.push({kind:'unknown',label:'路由待确认',detail:'系统连接表没有代理路径证据'});
    const targetDetail = flow.siteName ? `${flow.siteName} (${flow.host}) · ${flow.destination}` : `${flow.host} · ${flow.destination}`;
    nodes.push({kind:'server',label:'服务器',detail:targetDetail});
    return nodes;
  }
  function mergeSources(controller, system, systemError = null) {
    const flows=(controller?.flows || []).map(f=>({...f}));
    for(const flow of flows){
      const name=processName(system?.processes?.[flow.pid]);
      if(!flow.processSource&&name){flow.app=name;flow.processSource='windows-pid';}
      if(flow.protocol==='UDP'&&!flow.processSource){
        const metadata=flow.raw.metadata || {},port=metadata.source_port ?? metadata.sourcePort,ip=metadata.source_ip ?? metadata.sourceIP;
        const owners=(system?.udp_owners || []).filter(owner=>Number(owner.local_port)===Number(port)&&
          (owner.local_ip===ip||owner.local_ip==='0.0.0.0'||owner.local_ip==='::')&&(flow.pid==='未知'||flow.pid===owner.pid));
        if(owners.length&&new Set(owners.map(o=>o.pid)).size===1){
          const owner=owners[0],name=processName(system?.processes?.[owner.pid] || owner.process);
          flow.pid=owner.pid;flow.processOwner=owner;flow.app=name || `PID ${owner.pid}`;flow.processSource=name?'windows-udp':null;
        }
      }
    }
    for(const socket of system?.sockets || []){
      const f=systemFlow({...socket,process:system?.processes?.[socket.pid] || socket.process});
      const matches=flows.filter(c=>c.origin==='mihomo'&&c.protocol==='TCP'&&c.source===f.source&&
        (c.destination===f.destination||socket.remote_ip==='::1'||socket.remote_ip.startsWith('127.'))&&
        (c.pid==='未知'||c.pid===socket.pid));
      // 只合并可唯一对应的端点；没有控制器证据不推断直连。 / Merge unique tuples without inferring a direct route.
      if(matches.length===1){matches[0].system=socket;if(matches[0].pid==='未知')matches[0].pid=socket.pid;if(!matches[0].processSource){matches[0].app=f.app;matches[0].processSource=f.processSource;}}
      else flows.push(f);
    }
    return {...(controller || {time:system?.observed_at_utc || Date.now(),source:'live',events:[],rx:null,tx:null,download:null,upload:null,error:null}),flows,systemError};
  }
  function frame(raw, previous, time, source = 'live') {
    const prior = new Map((previous?.flows || []).map(f => [f.id, f]));
    const dt = previous ? (time - previous.time) / 1000 : 0;
    const flows = (raw.connections || []).map(normalize).map(f => {
      const p = prior.get(f.id);
      // 断点、新连接和计数回退只建基线。 / Rebaseline gaps, new flows and counter resets.
      const valid = dt > 0 && dt <= 5 && p && p.start === f.start && f.download >= p.download && f.upload >= p.upload;
      return {...f, rx:valid ? (f.download-p.download)/dt : null, tx:valid ? (f.upload-p.upload)/dt : null};
    });
    const download = number(raw.download_total), upload = number(raw.upload_total);
    const valid = dt > 0 && dt <= 5 && !previous?.error && previous?.source === source &&
      download >= previous.download && upload >= previous.upload;
    return {time, flows, source, events:[], error:null,
      download, upload, rx:valid ? (download-previous.download)/dt : null,
      tx:valid ? (upload-previous.upload)/dt : null};
  }
  const api = {endpoint, bytes, normalize, frame, systemFlow, mergeSources, hostKey, siteGroupKey, groupByHost, groupBySite, matchesQuery, resolveSite, routePath, flowKey, rateHistory};
  if (typeof module !== 'undefined') module.exports = api;
  root.StudioModel = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
