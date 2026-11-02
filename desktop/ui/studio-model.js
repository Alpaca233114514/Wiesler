(function(root) {
  'use strict';
  const number = n => Number.isFinite(Number(n)) && Number(n) >= 0 ? Number(n) : 0;
  const endpoint = (host, port) => `${String(host).includes(':') ? `[${host}]` : host}:${port || '?'}`;
  const bytes = n => {
    if (n == null || !Number.isFinite(n)) return '—';
    const i = Math.min(3, Math.floor(Math.log(Math.max(1, n)) / Math.log(1024)));
    return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${['B', 'KB', 'MB', 'GB'][i]}`;
  };
  function normalize(raw) {
    const m = raw.metadata || {}, value = (a,b) => m[a] ?? m[b];
    const host = m.host || value('destination_ip','destinationIP') || '未知目标';
    return {id:String(raw.id), start:raw.start || '', app:m.process || '未知进程', pid:m.pid ?? '未知',
      host, destination:endpoint(value('destination_ip','destinationIP') || host,value('destination_port','destinationPort')),
      source:endpoint(value('source_ip','sourceIP') || '未知',value('source_port','sourcePort')),
      port:value('destination_port','destinationPort') || '?', protocol:(m.network || '未知').toUpperCase(),
      route:(raw.chains || []).join(' → ') || '未知', rule:raw.rule || '未知',
      download:number(raw.download), upload:number(raw.upload), raw};
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
    return {time, flows, source, events:raw.events || [], error:null};
  }
  const demoSpecs = [
    ['browser-web','Browser','www.example.com','203.0.113.10',443,'TCP','WireGuard-demo',650000,24000],
    ['browser-api','Browser','api.example.com','203.0.113.20',443,'TCP','WireGuard-demo',82000,12000],
    ['sync','Cloud Sync','sync.example.net','198.51.100.42',443,'TCP','WireGuard-demo',12000,430000],
    ['dns','System','dns.example.net','192.0.2.53',53,'UDP','DIRECT',1200,700],
    ['update','System','updates.example.org','192.0.2.80',443,'TCP','DIRECT',175000,5000]
  ];
  function demo(tick) {
    const base = Date.UTC(2026,10,2,2,18,0);
    return {connections:demoSpecs.map((s,i) => ({id:s[0],start:new Date(base-i*18000).toISOString(),
      metadata:{process:s[1],pid:1200+i,host:s[2],destination_ip:s[3],destination_port:s[4],network:s[5],source_ip:'192.0.2.2',source_port:51000+i},
      chains:[s[6]],rule:s[6]==='DIRECT'?'Direct':'DomainSuffix',
      download:800000+i*250000+tick*s[7],upload:40000+tick*s[8]})),
      events:[{id:'browser-api',time:base+18000,level:'warning',text:'演示事件：TLS 握手超时',evidence:'场景脚本 · 请求 #18 · 超时阈值 3 秒'}].filter(e=>tick>=18)};
  }
  const api = {endpoint, bytes, normalize, frame, demo};
  if (typeof module !== 'undefined') module.exports = api;
  root.StudioModel = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
