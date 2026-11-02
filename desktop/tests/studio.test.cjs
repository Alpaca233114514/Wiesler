const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const M=require('../ui/studio-model.js');
const {snapshot}=require('./snapshot-fixture.cjs');

test('per-flow rates rebaseline new identities, gaps and counter rollbacks',()=>{
  const first=M.frame(snapshot(0),null,1000),next=M.frame(snapshot(1),first,2000);
  assert.equal(first.flows[0].rx,null);
  assert.equal(next.flows[0].rx,650000);
  assert.equal(next.flows[0].tx,24000);
  assert.equal(M.frame(snapshot(4),next,9000).flows[0].rx,null);
  assert.equal(M.frame(snapshot(0),next,3000).flows[0].rx,null);
  const changed=snapshot(2);changed.connections[0].start='another session';
  assert.equal(M.frame(changed,next,3000).flows[0].rx,null);
  assert.equal(M.frame(snapshot(2),M.frame({connections:[]},next,2500),3000).flows[0].rx,null);
});
test('desktop and Android field conventions preserve IPv6 endpoints',()=>{
  const f=M.normalize({id:'v6',metadata:{sourceIP:'::1',sourcePort:5555,destinationIP:'2001:db8::1',destinationPort:443}});
  assert.equal(f.destination,'[2001:db8::1]:443');assert.equal(f.source,'[::1]:5555');
});
function setup(invoke, discovery = async()=>({controllers:[],scanned:0}), system = async()=>({sockets:[],observed_at_utc:1000}), egress = async()=>({ip:'203.0.113.1',observed_at:1000})){
  class Element{
    constructor(){this.value='';this.textContent='';this.children=[];this.dataset={};this.attrs={};this.classList={toggle(){}};}
    append(...nodes){for(const node of nodes){node.parent=this;this.children.push(node);}}replaceChildren(...nodes){for(const node of this.children)node.parent=null;this.children=[];this.append(...nodes);}
    remove(){if(this.parent)this.parent.children=this.parent.children.filter(n=>n!==this);this.parent=null;}
    setAttribute(k,v){this.attrs[k]=v;}
  }
  const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  const timers=new Map();let timerId=0;
  const window={StudioModel:M,PrivacyModel:require('../ui/privacy-model.js'),innerWidth:1360,addEventListener(){},matchMedia:()=>({matches:false}),__TAURI__:invoke?{core:{invoke:(command,args)=>command==='discover_controllers'?discovery():command==='system_snapshot'?system():command==='egress_address'?egress(args):invoke(command,args)}}:null};
  const listeners={};
  const context=vm.createContext({window,document:{hidden:false,getElementById:get,createElement:()=>new Element(),createElementNS:()=>new Element(),querySelectorAll:()=>[],addEventListener:(event,fn)=>{(listeners[event] ||= []).push(fn);}},setTimeout:(fn,delay)=>{timers.set(++timerId,{fn,delay});return timerId;},clearTimeout:id=>timers.delete(id)});
  vm.runInContext(fs.readFileSync('desktop/ui/monitor-tools.js','utf8'),context);
  vm.runInContext(fs.readFileSync('desktop/ui/studio.js','utf8'),context);
  get('controller').value='http://127.0.0.1:9090';
  const content=el=>[el.textContent,...el.children.map(content)].join(' ');
  return {get,content,hide:hidden=>{context.document.hidden=hidden;for(const fn of listeners.visibilitychange||[])fn();},tick:async()=>{const [id,item]=[...timers.entries()].find(([,t])=>t.delay===1000)||[...timers.entries()].find(([,t])=>t.delay===250)||[];if(item){const due=[...timers.entries()].filter(([,t])=>t.delay===item.delay);await Promise.all(due.map(([key,t])=>{timers.delete(key);return t.fn();}));}}};
}
test('freeze keeps inspector evidence fixed while collection advances, then resumes',async()=>{
  const ui=await setupWithSamples();ui.get('freeze').onclick();const before=ui.content(ui.get('inspect-content'));
  await ui.tick();assert.equal(ui.get('snapshot-count').textContent,'26 次快照');
  assert.equal(ui.content(ui.get('inspect-content')),before);
  ui.get('now').onclick();assert.notEqual(ui.content(ui.get('inspect-content')),before);
  for(let i=0;i<70;i++)await ui.tick();assert.equal(ui.get('snapshot-count').textContent,'60 次快照');
});
test('search and protocol filter update graph, list and inspector together',async()=>{
  const ui=await setupWithSamples();ui.get('flow-protocol').value='UDP';ui.get('flow-protocol').onchange();
  assert.equal(ui.get('flow-count').textContent,'1');assert.equal(ui.get('selection-title').textContent,'dns.example.net');
  ui.get('flow-search').value='no match';ui.get('flow-search').oninput();
  assert.equal(ui.get('flow-count').textContent,'0');assert.equal(ui.get('selection-title').textContent,'选择一条连接');
});
test('live failure clears current data and recovery rebaselines counters',async()=>{
  const queue=[{...snapshot(1),observed_at:1000},new Error('HTTP 401'),{...snapshot(9),observed_at:3000}];
  const ui=setup(async()=>{const item=queue.shift();if(item instanceof Error)throw item;return item;});
  ui.get('controller-form').onsubmit({preventDefault(){}});await new Promise(r=>setImmediate(r));
  await ui.tick();assert.equal(ui.get('flow-count').textContent,'0');assert.match(ui.get('source-state').textContent,/HTTP 401/);
  await ui.tick();assert.equal(ui.get('flow-count').textContent,'5');assert.match(ui.content(ui.get('inspect-content')),/等待连续快照/);
});

const flush=()=>new Promise(r=>setImmediate(r));
const connect=ui=>ui.get('controller-form').onsubmit({preventDefault(){}});
test('desktop starts live and scans; views share a single collector and preserve selection',async()=>{
  const calls=[];let tick=0;
  const ui=setup(async(command,args)=>{calls.push({command,args});return {...snapshot(tick),observed_at:1000*(++tick),http_status:200};});
  assert.equal(calls.length,0);assert.equal(ui.get('flow-count').textContent,'0');
  assert.equal(ui.get('freeze').disabled,true);ui.get('freeze').onclick();
  connect(ui);await flush();
  assert.equal(ui.get('monitor-count').textContent,'5');assert.equal(ui.get('monitor-rx').textContent,'等待差分');
  const selected=ui.get('selection-title').textContent;
  ui.get('view-monitor').onclick();ui.get('view-diagnostics').onclick();ui.get('view-graph').onclick();
  assert.equal(calls.length,1);assert.equal(ui.get('selection-title').textContent,selected);
  await ui.tick();assert.equal(calls.length,2);assert.equal(calls[1].command,'snapshot');
  assert.equal(ui.get('request-log-rows').children.length,2);assert.notEqual(ui.get('monitor-rx').textContent,'等待差分');
  assert.match(ui.get('trend-rx').attrs.d,/M800,/);
});
test('editing address cannot silently switch active controller; reconnect drains old request',async()=>{
  const calls=[],pending=[];const ui=setup((command,args)=>{calls.push(args.controller);return new Promise(r=>pending.push(r));});
  connect(ui);ui.get('controller').value='http://127.0.0.1:9097';connect(ui);
  assert.deepEqual(calls,['http://127.0.0.1:9090']);
  pending.shift()({...snapshot(0),observed_at:1000,http_status:200});await flush();
  assert.equal(ui.get('request-log-rows').children.length,0);
  const p=ui.tick();assert.deepEqual(calls,['http://127.0.0.1:9090','http://127.0.0.1:9097']);
  pending.shift()({...snapshot(1),observed_at:2000,http_status:200});await p;
  ui.get('controller').value='http://127.0.0.1:9999';
  const p2=ui.tick();assert.equal(calls.at(-1),'http://127.0.0.1:9097');
  pending.shift()({...snapshot(2),observed_at:3000,http_status:200});await p2;
});
test('pause keeps evidence and resume rebaselines even for a short gap',async()=>{
  let tick=0;const ui=setup(async()=>({...snapshot(tick),observed_at:1000*(++tick),http_status:200}));
  connect(ui);await flush();await ui.tick();
  const before=ui.content(ui.get('inspect-content'));ui.get('capture-toggle').onclick();await ui.tick();
  assert.equal(tick,2);assert.equal(ui.content(ui.get('inspect-content')),before);
  ui.get('capture-toggle').onclick();await flush();
  assert.equal(tick,3);assert.equal(ui.get('monitor-rx').textContent,'等待差分');
  assert.match(ui.content(ui.get('inspect-content')),/等待连续快照/);
});
test('hide during first request ignores response and resumes sampling when visible',async()=>{
  let resolve,calls=0;const ui=setup(()=>{calls++;return new Promise(r=>resolve=r);});
  connect(ui);ui.hide(true);resolve({...snapshot(0),observed_at:1000});await flush();
  assert.equal(ui.get('flow-count').textContent,'0');ui.hide(false);assert.equal(calls,2);
  resolve({...snapshot(1),observed_at:2000,http_status:200});await flush();
  assert.equal(ui.get('flow-count').textContent,'5');assert.equal(ui.get('monitor-rx').textContent,'等待差分');
});
test('frozen graph keeps evidence while monitoring exposes live HTTP failure and recovery',async()=>{
  const queue=[{...snapshot(1),observed_at:1000,http_status:200},{message:'forbidden',http_status:403},{...snapshot(3),observed_at:3000,http_status:200}];
  const ui=setup(async()=>{const r=queue.shift();if(r.message)throw r;return r;});
  connect(ui);await flush();ui.get('freeze').onclick();const before=ui.content(ui.get('inspect-content'));
  await ui.tick();assert.equal(ui.content(ui.get('inspect-content')),before);assert.match(ui.get('source-state').textContent,/forbidden/);
  assert.equal(ui.get('monitor-count').textContent,'—');assert.equal(ui.get('request-log-rows').children[0].children[2].textContent,'HTTP 403');
  ui.get('flow-search').oninput();assert.match(ui.get('source-state').textContent,/forbidden/);
  await ui.tick();assert.equal(ui.get('monitor-rx').textContent,'等待差分');assert.equal(ui.get('request-log-rows').children.length,3);
});
test('integrated diagnostics and ICMP dispatch real command names and arguments',async()=>{
  const calls=[];const ui=setup(async(command,args)=>{
    calls.push({command,args});
    if(command==='sessions')return [{session_id:'session-1',started_at_utc:1000,event_count:2}];
    if(command==='diagnose')return '# Session evidence';
    if(command==='ping')return {outcome:'reply',rtt_ms:2,message:'ok'};
    throw new Error('unexpected command');
  });
  ui.get('view-diagnostics').onclick();ui.get('database').value='evidence.sqlite3';
  await ui.get('database-form').onsubmit({preventDefault(){}});ui.get('session').value='session-1';await ui.get('diagnose').onclick();
  assert.equal(ui.get('report').textContent,'# Session evidence');
  assert.equal(calls[1].args.database,'evidence.sqlite3');assert.equal(calls[1].args.session,'session-1');
  ui.get('view-monitor').onclick();ui.get('loss-target').value='127.0.0.1';ui.get('loss-form').onsubmit({preventDefault(){}});await flush();
  assert.equal(calls[2].command,'ping');assert.equal(calls[2].args.targetIp,'127.0.0.1');assert.equal(ui.get('loss-rate').textContent,'0.0%');
  ui.hide(true);assert.equal(ui.get('loss-stop').disabled,true);
});
test('live sources never inherit synthetic events and global counters rebaseline gaps',()=>{
  const a=M.frame(snapshot(20),null,1000),b=M.frame(snapshot(21),a,2000);
  assert.equal(a.events.length,0);assert.ok(b.rx>0);
  assert.equal(M.frame(snapshot(22),b,9000).rx,null);
  assert.equal(M.frame(snapshot(0),b,3000).rx,null);
});
test('browser preview stays empty without native backend',()=>{
  const ui=setup(undefined);connect(ui);
  assert.match(ui.get('source-state').textContent,/桌面程序/);assert.equal(ui.get('flow-count').textContent,'0');
  assert.equal(M.demo,undefined);assert.doesNotMatch(fs.readFileSync('desktop/ui/studio.html','utf8'),/id="demo"|演示数据/);assert.equal(ui.get('request-log-rows').children.length,0);
});

test('one verified controller auto-connects without a typed address',async()=>{
  const calls=[];const ui=setup(async(command,args)=>{calls.push({command,args});return {...snapshot(0),observed_at:1000,http_status:200};},async()=>({controllers:[{controller:'http://127.0.0.1:9191',version:'v1',ready:true}]}));
  await flush();assert.equal(calls.length,1);assert.equal(calls[0].args.controller,'http://127.0.0.1:9191');
  assert.equal(ui.get('controller-address').textContent,'http://127.0.0.1:9191');assert.equal(ui.get('flow-count').textContent,'5');
});
test('multiple controllers require a selection; unverified services never auto-connect',async()=>{
  const calls=[];const ui=setup(async(command,args)=>{calls.push(args);return {...snapshot(0),observed_at:1000};},async()=>({controllers:[{controller:'http://127.0.0.1:9191',version:'v1',ready:true},{controller:'http://127.0.0.1:9192',version:'v2',ready:true}]}));
  await flush();assert.equal(calls.length,0);assert.match(ui.get('source-state').textContent,/2 个控制器/);
  ui.get('controller-choice').value='http://127.0.0.1:9192';ui.get('connect-discovered').onclick();await flush();assert.equal(calls[0].controller,'http://127.0.0.1:9192');
  const protectedUi=setup(()=>{throw new Error('must not connect');},async()=>({controllers:[{controller:'http://127.0.0.1:9999',ready:false,configured:false}]}));
  await flush();assert.match(protectedUi.get('source-state').textContent,/需要鉴权/);assert.equal(protectedUi.get('flow-count').textContent,'0');
});
test('late discovery cannot override a manually selected controller',async()=>{
  let resolve;const calls=[];const ui=setup(async(command,args)=>{calls.push(args);return {...snapshot(0),observed_at:1000};},()=>new Promise(r=>resolve=r));
  connect(ui);await flush();resolve({controllers:[{controller:'http://127.0.0.1:9191',ready:true}]});await flush();
  assert.equal(calls.length,1);assert.equal(calls[0].controller,'http://127.0.0.1:9090');
});
test('scan failures and empty results retain a usable rescan action',async()=>{
  let scans=0;const ui=setup(()=>{throw new Error('must not connect');},async()=>{if(++scans===1)throw new Error('unavailable');return {controllers:[],scanned:4};});
  await flush();assert.match(ui.get('source-state').textContent,/扫描失败/);assert.equal(ui.get('discover').disabled,false);
  await ui.get('discover').onclick();assert.match(ui.get('source-state').textContent,/未发现/);assert.equal(ui.get('discover').disabled,false);
});

const socketSample={process:'direct-app.exe',pid:42,process_started_at:'2026-11-02T00:00:00Z',local_ip:'192.0.2.2',local_port:55000,remote_ip:'203.0.113.50',remote_port:443,state:'Established'};
test('system connections work without a controller and retain unknown routing and byte counts',async()=>{
  const ui=setup(()=>{throw new Error('no controller expected');},async()=>({controllers:[],scanned:3}),async()=>({sockets:[socketSample],observed_at_utc:1000}));
  await flush();assert.equal(ui.get('flow-count').textContent,'1');assert.match(ui.content(ui.get('inspect-content')),/系统 TCP/);
  assert.match(ui.content(ui.get('inspect-content')),/路由待确认/);assert.equal(ui.get('monitor-rx').textContent,'—');
  ui.get('flow-source').value='proxy';ui.get('flow-source').onchange();assert.equal(ui.get('flow-count').textContent,'0');
  ui.get('flow-source').value='system';ui.get('flow-source').onchange();assert.equal(ui.get('flow-count').textContent,'1');
  ui.get('view-settings').onclick();assert.equal(ui.get('settings').hidden,false);assert.equal(ui.get('graph-view').hidden,true);
});
test('system-only snapshots do not invent byte measurements or confirmed direct routes',()=>{
  const flow=M.mergeSources(null,{sockets:[socketSample],observed_at_utc:1000}).flows[0];
  assert.equal(flow.download,null);assert.equal(flow.rx,null);assert.equal(flow.routing,'unknown');assert.equal(flow.origin,'system');
});
test('matching system tuples enrich controller evidence once; ambiguous tuples remain separate',()=>{
  const raw=snapshot(1),first=raw.connections[0];first.metadata.pid=42;first.chains=['DIRECT'];
  const socket={...socketSample,local_port:first.metadata.source_port,remote_ip:first.metadata.destination_ip};
  const frame=M.frame(raw,null,1000),merged=M.mergeSources(frame,{sockets:[socket],observed_at_utc:1000});
  assert.equal(merged.flows.length,5);assert.equal(merged.flows[0].routing,'direct');assert.equal(merged.flows[0].system.pid,42);
  raw.connections.push({...first,id:'ambiguous'});
  assert.equal(M.mergeSources(M.frame(raw,null,1000),{sockets:[socket]}).flows.length,7);
  assert.equal(M.mergeSources(frame,{sockets:[{...socket,remote_ip:'203.0.113.99'}]}).flows.length,6);
});
test('controller failure does not discard independently observed system connections',async()=>{
  const ui=setup(async()=>{throw {message:'forbidden',http_status:403};},async()=>({controllers:[],scanned:0}),async()=>({sockets:[socketSample],observed_at_utc:1000}));
  await flush();connect(ui);await flush();
  assert.equal(ui.get('flow-count').textContent,'1');assert.match(ui.get('source-state').textContent,/forbidden/);
  assert.match(ui.content(ui.get('inspect-content')),/direct-app.exe/);
});

test('pause during discovery unlocks rescan and resumes using the pending scan safely',async()=>{
  let resolve,scans=0;const calls=[];
  const ui=setup(async(command,args)=>{calls.push(args);return {...snapshot(0),observed_at:1000};},()=>{scans++;return new Promise(r=>resolve=r);});
  ui.get('capture-toggle').onclick();assert.equal(ui.get('discover').disabled,false);
  ui.get('capture-toggle').onclick();assert.equal(scans,1);
  resolve({controllers:[{controller:'http://127.0.0.1:9191',ready:true,version:'v1'}]});await flush();
  assert.equal(calls.length,1);assert.equal(calls[0].controller,'http://127.0.0.1:9191');
});

test('route strips show computer-to-server order without mutating controller evidence',()=>{
  const raw={id:'route',metadata:{host:'example.org'},chains:['VPS-Reality-443','VPS-Auto','Overseas']};
  const path=M.routePath(M.normalize(raw));
  assert.deepEqual(path.map(n=>n.label),['电脑','Overseas','VPS-Auto','VPS-Reality-443','服务器']);
  assert.deepEqual(raw.chains,['VPS-Reality-443','VPS-Auto','Overseas']);
  assert.deepEqual(M.routePath(M.normalize({...raw,chains:['DIRECT']})).map(n=>n.label),['电脑','服务器']);
  assert.deepEqual(M.routePath(M.systemFlow(socketSample)).map(n=>n.label),['电脑','路由待确认','服务器']);
});
test('host grouping ignores port, case and trailing dot while keeping every connection',()=>{
  const flows=[M.normalize({id:'a',metadata:{host:'Example.org',destination_port:443}}),M.normalize({id:'b',metadata:{host:'example.org.',destination_port:8443}}),M.normalize({id:'c',metadata:{host:'other.example.org',destination_port:443}})];
  const groups=M.groupByHost(flows);assert.equal(groups.length,2);
  assert.deepEqual(groups[0].flows.map(f=>f.port),[443,8443]);assert.equal(flows.length,3);
});
test('stacked domain node exposes individual ports in inspector and keeps table rows',async()=>{
  const raw=snapshot(0);raw.connections=[raw.connections[0],{...raw.connections[0],id:'second-port',metadata:{...raw.connections[0].metadata,destination_port:8443}}];
  const ui=setup(async()=>({...raw,observed_at:1000,http_status:200}));
  connect(ui);await flush();
  const targets=ui.get('nodes').children.filter(n=>n.attrs['data-key']?.startsWith('host:'));
  assert.equal(targets.length,1);assert.match(ui.content(targets[0]),/2 条连接 · 2 个端口/);
  assert.equal(ui.get('flow-rows').children.length,2);assert.equal(ui.get('group-session').hidden,false);
  ui.get('group-session').value='second-port';ui.get('group-session').onchange();
  assert.match(ui.get('selection-endpoint').textContent,/:8443$/);
  assert.match(ui.content(ui.get('inspect-content')),/电脑.*WireGuard-test.*服务器/);
  assert.match(ui.content(ui.get('flow-rows').children[0].children[3]),/等待速率样本/);
});

test('missing process names use Windows PID mapping or a unique socket owner',()=>{
  const raw={id:'unnamed',metadata:{pid:42,source_ip:'192.0.2.2',source_port:55000,destination_ip:'203.0.113.50',destination_port:443,network:'tcp'},chains:['DIRECT']};
  let frame=M.mergeSources(M.frame({connections:[raw]},null,1000),{processes:{42:'browser.exe'},sockets:[]});
  assert.equal(frame.flows[0].app,'browser.exe');assert.equal(frame.flows[0].processSource,'windows-pid');
  raw.metadata.pid=0;
  frame=M.mergeSources(M.frame({connections:[raw]},null,1000),{processes:{42:'browser.exe'},sockets:[socketSample]});
  assert.equal(frame.flows.length,1);assert.equal(frame.flows[0].app,'browser.exe');assert.equal(frame.flows[0].pid,42);
  assert.equal(frame.flows[0].processSource,'windows-socket');
  raw.metadata.pid=99;
  frame=M.mergeSources(M.frame({connections:[raw]},null,1000),{processes:{42:'unrelated.exe'},sockets:[socketSample]});
  assert.equal(frame.flows[0].app,'PID 99');assert.equal(frame.flows[0].processSource,null);
});
test('egress probes separate system and proxy routes and discard old controller results',async()=>{
  const pending=[];const ui=setup(async()=>({...snapshot(0),observed_at:1000}),async()=>({controllers:[],scanned:0}),undefined,args=>new Promise(resolve=>pending.push({args,resolve})));
  connect(ui);await flush();assert.equal(pending.length,2);
  assert.equal(pending[0].args.viaProxy,false);assert.equal(pending[1].args.viaProxy,true);
  ui.get('controller').value='http://127.0.0.1:9191';connect(ui);await flush();
  pending[2].resolve({ip:'203.0.113.2',observed_at:2000});pending[3].resolve({ip:'203.0.113.3',observed_at:2000});await flush();
  pending[0].resolve({ip:'198.51.100.1',observed_at:1000});pending[1].resolve({ip:'198.51.100.2',observed_at:1000});await flush();
  assert.equal(ui.get('egress-system-ip').textContent,'203.0.113.2');assert.equal(ui.get('egress-proxy-ip').textContent,'203.0.113.3');assert.equal(ui.get('egress-refresh').disabled,false);
});
test('egress failure clears stale addresses without hiding the working route',async()=>{
  const ui=setup(async()=>({...snapshot(0),observed_at:1000}),async()=>({controllers:[],scanned:0}),undefined,async args=>{if(args.viaProxy)throw new Error('timeout');return {ip:'203.0.113.4',observed_at:1000};});
  connect(ui);await flush();assert.equal(ui.get('egress-system-ip').textContent,'203.0.113.4');assert.equal(ui.get('egress-proxy-ip').textContent,'—');assert.match(ui.get('egress-proxy-status').textContent,/timeout/);
});

test('UDP unknown processes resolve from unique Windows endpoint owners, not an arbitrary PID',()=>{
  const raw={id:'udp',metadata:{network:'udp',source_ip:'192.0.2.2',source_port:55001},chains:['DIRECT']};
  const owners=[{pid:77,process:'dns-client.exe',local_ip:'0.0.0.0',local_port:55001}];
  let frame=M.mergeSources(M.frame({connections:[raw]},null,1000),{sockets:[],udp_owners:owners});
  assert.equal(frame.flows[0].app,'dns-client.exe');assert.equal(frame.flows[0].processSource,'windows-udp');
  owners.push({pid:78,process:'another.exe',local_ip:'0.0.0.0',local_port:55001});
  frame=M.mergeSources(M.frame({connections:[raw]},null,1000),{sockets:[],udp_owners:owners});
  assert.equal(frame.flows[0].processSource,null);assert.equal(frame.flows[0].pid,'未知');
});

test('egress overview shows location and ISP while unknown geography keeps a valid IP',async()=>{
  const ui=setup(async()=>({...snapshot(0),observed_at:1000}),async()=>({controllers:[],scanned:0}),undefined,async args=>({ip:args.viaProxy?'203.0.113.5':'203.0.113.4',observed_at:1000,location:args.viaProxy?{country:'Singapore',country_code:'SG',region:'',city:'Singapore',isp:'Example ISP'}:null,location_error:args.viaProxy?null:'lookup unavailable'}));
  connect(ui);await flush();assert.match(ui.get('egress-proxy-location').textContent,/新加坡.*Example ISP/);
  assert.equal(ui.get('egress-system-ip').textContent,'203.0.113.4');assert.equal(ui.get('egress-system-location').textContent,'地理位置未知');
});

test('per-connection rate history keeps gaps and separates reused IDs',()=>{
  const first=M.frame(snapshot(0),null,1000),second=M.frame(snapshot(1),first,2000),fourth=M.frame(snapshot(3),second,4000);
  const history=M.rateHistory([first,second,{flows:[]},fourth]),series=history.get(M.flowKey(second.flows[0]));
  assert.equal(series[0],null);assert.equal(series[1][0],650000);assert.equal(series[2],null);assert.equal(series[3][1],24000);
  assert.equal(history.get(M.flowKey({...second.flows[0],start:'different'})),undefined);
});
test('session rows show speed curves, while traffic dots move in opposite directions and stop when frozen',async()=>{
  const ui=await setupWithSamples();const chart=ui.get('flow-rows').children[0].children[3].children[0];
  assert.equal(chart.attrs.class,'connection-trend');assert.ok(chart.children.some(n=>n.attrs.class==='trend-down'));assert.ok(chart.children.some(n=>n.attrs.class==='trend-up'));
  const motions=ui.get('pulses').children.flatMap(node=>node.children).filter(n=>n.attrs.repeatCount==='indefinite');
  assert.ok(motions.some(n=>n.attrs.keyPoints==='0;1'));assert.ok(motions.some(n=>n.attrs.keyPoints==='1;0'));
  ui.get('freeze').onclick();assert.equal(ui.get('pulses').children.some(n=>n.attrs.class?.startsWith('pulse')),false);
  const before=ui.content(ui.get('flow-rows'));ui.get('flow-search').oninput();assert.equal(ui.content(ui.get('flow-rows')),before);
  ui.get('now').onclick();ui.get('flow-animation').checked=false;ui.get('flow-animation').onchange();
  assert.equal(ui.get('pulses').children.some(n=>n.attrs.class?.startsWith('pulse')),false);
});

test('sampling keeps moving dots alive until they enter their destination nodes',async()=>{
  const ui=await setupWithSamples(),dot=ui.get('pulses').children[0],motion=dot.children[0];
  await ui.tick();assert.equal(ui.get('pulses').children[0],dot);
  assert.equal(motion.attrs.begin,'indefinite');assert.match(motion.attrs.path,/ L .* L /);
});


test('privacy view applies explicit policy, exports live provenance and preserves evidence as text',async()=>{
  const ui=await setupWithSamples();ui.get('view-privacy').onclick();
  assert.equal(ui.get('privacy-view').hidden,false);
  assert.match(ui.content(ui.get('privacy-findings')),/DNS 端口连接/);
  ui.get('privacy-proxy').checked=true;ui.get('privacy-threshold').value='100';
  ui.get('privacy-policy').onsubmit({preventDefault(){}});
  assert.match(ui.content(ui.get('privacy-findings')),/DIRECT 与预期代理不符/);
  ui.get('privacy-report').onclick();
  const report=JSON.parse(ui.get('privacy-report-result').value);
  assert.equal(report.source,'live');assert.equal(report.collection,'running');
  assert.equal(report.policy.expectProxy,true);
  assert.ok(report.findings.some(f=>f.evidence.connectionId==='dns'));
  ui.get('privacy-environment').onclick();
  assert.match(ui.content(ui.get('privacy-environment-result')),/语言偏好/);
  assert.match(ui.content(ui.get('privacy-environment-result')),/时区/);
  assert.match(ui.content(ui.get('privacy-checks')),/建议：/);
  assert.match(ui.get('privacy-summary-title').textContent,/需要你核对/);
  assert.doesNotMatch(ui.content(ui.get('privacy-environment-result')),/\"userAgent\"/);
});


test('privacy evidence remains expanded across live refreshes',async()=>{
  const ui=await setupWithSamples();
  const detail=ui.get('privacy-findings').children[0].children[2];
  detail.open=true;detail.ontoggle();
  await ui.tick();
  assert.equal(ui.get('privacy-findings').children[0].children[2].open,true);
});


test('actual privacy inspection invokes native DNS and both egress paths, then reports measured results',async()=>{
  const calls=[];
  const ui=setup(async(command)=>{
    calls.push(command);
    if(command==='inspect_dns')return {observedAt:1000,resolvers:[{server:'198.18.0.2',configuredInterface:'Meta',description:'Tunnel',hardware:false,routeInterface:'Meta',nextHop:'0.0.0.0'}],queries:[{server:'198.18.0.2',domain:'example.com',addresses:['198.18.0.10'],elapsedMs:3,error:null}]};
    return {...snapshot(0),observed_at:1000,http_status:200};
  },async()=>({controllers:[],scanned:0}),async()=>({sockets:[],observed_at_utc:1000}),async args=>({ip:args.viaProxy?'203.0.113.2':'203.0.113.1',observed_at:1000}));
  connect(ui);await flush();ui.get('view-privacy').onclick();await flush();
  assert.ok(calls.includes('inspect_dns'));
  assert.match(ui.get('privacy-active-title').textContent,/本轮检测完成/);
  const result=ui.content(ui.get('privacy-active-results'));
  assert.match(result,/198.18.0.2/);assert.match(result,/198.18.0.10/);
  assert.match(result,/两条探测路径出口不同/);
  connect(ui);assert.equal(ui.get('privacy-active-results').children.length,0);
});

test('actual inspection errors stay visible and late results cannot cross a controller change',async()=>{
  let resolveDns;
  const ui=setup(async command=>{
    if(command==='inspect_dns')return new Promise(r=>{resolveDns=r;});
    return {...snapshot(0),observed_at:1000,http_status:200};
  },async()=>({controllers:[],scanned:0}),async()=>({sockets:[],observed_at_utc:1000}),async()=>{throw new Error('出口服务超时');});
  connect(ui);await flush();ui.get('view-privacy').onclick();await flush();
  assert.match(ui.content(ui.get('privacy-active-results')),/出口服务超时/);
  connect(ui);resolveDns({resolvers:[],queries:[]});await flush();
  assert.equal(ui.get('privacy-active-results').children.length,0);
});

async function setupWithSamples(){
  let tick=0;
  const ui=setup(async command=>{
    if(command==='inspect_dns')return {resolvers:[],queries:[]};
    return {...snapshot(tick),observed_at:1000*(++tick),http_status:200};
  });
  connect(ui);await flush();
  for(let i=0;i<24;i++)await ui.tick();
  return ui;
}
