const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const M=require('../ui/studio-model.js');

test('per-flow rates rebaseline new identities, gaps and counter rollbacks',()=>{
  const first=M.frame(M.demo(0),null,1000),next=M.frame(M.demo(1),first,2000);
  assert.equal(first.flows[0].rx,null);
  assert.equal(next.flows[0].rx,650000);
  assert.equal(next.flows[0].tx,24000);
  assert.equal(M.frame(M.demo(4),next,9000).flows[0].rx,null);
  assert.equal(M.frame(M.demo(0),next,3000).flows[0].rx,null);
  const changed=M.demo(2);changed.connections[0].start='another session';
  assert.equal(M.frame(changed,next,3000).flows[0].rx,null);
  assert.equal(M.frame(M.demo(2),M.frame({connections:[]},next,2500),3000).flows[0].rx,null);
});
test('desktop and Android field conventions preserve IPv6 endpoints',()=>{
  const f=M.normalize({id:'v6',metadata:{sourceIP:'::1',sourcePort:5555,destinationIP:'2001:db8::1',destinationPort:443}});
  assert.equal(f.destination,'[2001:db8::1]:443');assert.equal(f.source,'[::1]:5555');
});
function setup(invoke, demo = true, discovery = async()=>({controllers:[],scanned:0})){
  class Element{
    constructor(){this.value='';this.textContent='';this.children=[];this.dataset={};this.attrs={};this.classList={toggle(){}};}
    append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}
    setAttribute(k,v){this.attrs[k]=v;}
  }
  const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  const timers=new Map();let timerId=0;
  const window={StudioModel:M,innerWidth:1360,addEventListener(){},matchMedia:()=>({matches:false}),__TAURI__:invoke?{core:{invoke:(command,args)=>command==='discover_controllers'?discovery():invoke(command,args)}}:null};
  const listeners={};
  const context=vm.createContext({window,document:{hidden:false,getElementById:get,createElement:()=>new Element(),createElementNS:()=>new Element(),querySelectorAll:()=>[],addEventListener:(event,fn)=>{(listeners[event] ||= []).push(fn);}},setTimeout:fn=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id)});
  vm.runInContext(fs.readFileSync('desktop/ui/monitor-tools.js','utf8'),context);
  vm.runInContext(fs.readFileSync('desktop/ui/studio.js','utf8'),context);
  get('controller').value='http://127.0.0.1:9090';
  if(demo)get('demo').onclick();
  const content=el=>[el.textContent,...el.children.map(content)].join(' ');
  return {get,content,hide:hidden=>{context.document.hidden=hidden;for(const fn of listeners.visibilitychange||[])fn();},tick:async()=>{const [id,fn]=timers.entries().next().value||[];if(fn){timers.delete(id);await fn();}}};
}
test('freeze keeps inspector evidence fixed while collection advances, then resumes',async()=>{
  const ui=setup();ui.get('freeze').onclick();const before=ui.content(ui.get('inspect-content'));
  await ui.tick();assert.equal(ui.get('snapshot-count').textContent,'26 次快照');
  assert.equal(ui.content(ui.get('inspect-content')),before);
  ui.get('now').onclick();assert.notEqual(ui.content(ui.get('inspect-content')),before);
  for(let i=0;i<70;i++)await ui.tick();assert.equal(ui.get('snapshot-count').textContent,'60 次快照');
});
test('search and protocol filter update graph, list and inspector together',()=>{
  const ui=setup();ui.get('flow-protocol').value='UDP';ui.get('flow-protocol').onchange();
  assert.equal(ui.get('flow-count').textContent,'1');assert.equal(ui.get('selection-title').textContent,'dns.example.net');
  ui.get('flow-search').value='no match';ui.get('flow-search').oninput();
  assert.equal(ui.get('flow-count').textContent,'0');assert.equal(ui.get('selection-title').textContent,'选择一条连接');
});
test('switching to demo discards outstanding live response',async()=>{
  let resolve;const ui=setup(()=>new Promise(r=>resolve=r));
  ui.get('live').onclick();ui.get('controller-form').onsubmit({preventDefault(){}});
  ui.get('demo').onclick();resolve({observed_at:1000,connections:[]});await new Promise(r=>setImmediate(r));
  assert.equal(ui.get('source-state').textContent,'演示数据');assert.equal(ui.get('flow-count').textContent,'5');
});
test('live failure clears current data and recovery rebaselines counters',async()=>{
  const queue=[{...M.demo(1),observed_at:1000},new Error('HTTP 401'),{...M.demo(9),observed_at:3000}];
  const ui=setup(async()=>{const item=queue.shift();if(item instanceof Error)throw item;return item;});
  ui.get('live').onclick();ui.get('controller-form').onsubmit({preventDefault(){}});await new Promise(r=>setImmediate(r));
  await ui.tick();assert.equal(ui.get('flow-count').textContent,'0');assert.match(ui.get('source-state').textContent,/HTTP 401/);
  await ui.tick();assert.equal(ui.get('flow-count').textContent,'5');assert.match(ui.content(ui.get('inspect-content')),/等待连续快照/);
});

const flush=()=>new Promise(r=>setImmediate(r));
const connect=ui=>ui.get('controller-form').onsubmit({preventDefault(){}});
test('desktop starts live and scans; views share a single collector and preserve selection',async()=>{
  const calls=[];let tick=0;
  const ui=setup(async(command,args)=>{calls.push({command,args});return {...M.demo(tick),observed_at:1000*(++tick),http_status:200};},false);
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
  const calls=[],pending=[];const ui=setup((command,args)=>{calls.push(args.controller);return new Promise(r=>pending.push(r));},false);
  connect(ui);ui.get('controller').value='http://127.0.0.1:9097';connect(ui);
  assert.deepEqual(calls,['http://127.0.0.1:9090']);
  pending.shift()({...M.demo(0),observed_at:1000,http_status:200});await flush();
  assert.equal(ui.get('request-log-rows').children.length,0);
  const p=ui.tick();assert.deepEqual(calls,['http://127.0.0.1:9090','http://127.0.0.1:9097']);
  pending.shift()({...M.demo(1),observed_at:2000,http_status:200});await p;
  ui.get('controller').value='http://127.0.0.1:9999';
  const p2=ui.tick();assert.equal(calls.at(-1),'http://127.0.0.1:9097');
  pending.shift()({...M.demo(2),observed_at:3000,http_status:200});await p2;
});
test('pause keeps evidence and resume rebaselines even for a short gap',async()=>{
  let tick=0;const ui=setup(async()=>({...M.demo(tick),observed_at:1000*(++tick),http_status:200}),false);
  connect(ui);await flush();await ui.tick();
  const before=ui.content(ui.get('inspect-content'));ui.get('capture-toggle').onclick();await ui.tick();
  assert.equal(tick,2);assert.equal(ui.content(ui.get('inspect-content')),before);
  ui.get('capture-toggle').onclick();await flush();
  assert.equal(tick,3);assert.equal(ui.get('monitor-rx').textContent,'等待差分');
  assert.match(ui.content(ui.get('inspect-content')),/等待连续快照/);
});
test('hide during first request ignores response and resumes sampling when visible',async()=>{
  let resolve,calls=0;const ui=setup(()=>{calls++;return new Promise(r=>resolve=r);},false);
  connect(ui);ui.hide(true);resolve({...M.demo(0),observed_at:1000});await flush();
  assert.equal(ui.get('flow-count').textContent,'0');ui.hide(false);assert.equal(calls,2);
  resolve({...M.demo(1),observed_at:2000,http_status:200});await flush();
  assert.equal(ui.get('flow-count').textContent,'5');assert.equal(ui.get('monitor-rx').textContent,'等待差分');
});
test('frozen graph keeps evidence while monitoring exposes live HTTP failure and recovery',async()=>{
  const queue=[{...M.demo(1),observed_at:1000,http_status:200},{message:'forbidden',http_status:403},{...M.demo(3),observed_at:3000,http_status:200}];
  const ui=setup(async()=>{const r=queue.shift();if(r.message)throw r;return r;},false);
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
  },false);
  ui.get('view-diagnostics').onclick();ui.get('database').value='evidence.sqlite3';
  await ui.get('database-form').onsubmit({preventDefault(){}});ui.get('session').value='session-1';await ui.get('diagnose').onclick();
  assert.equal(ui.get('report').textContent,'# Session evidence');
  assert.equal(calls[1].args.database,'evidence.sqlite3');assert.equal(calls[1].args.session,'session-1');
  ui.get('view-monitor').onclick();ui.get('loss-target').value='127.0.0.1';ui.get('loss-form').onsubmit({preventDefault(){}});await flush();
  assert.equal(calls[2].command,'ping');assert.equal(calls[2].args.targetIp,'127.0.0.1');assert.equal(ui.get('loss-rate').textContent,'0.0%');
  ui.hide(true);assert.equal(ui.get('loss-stop').disabled,true);
});
test('live sources never inherit demo events and global counters rebaseline gaps',()=>{
  const a=M.frame(M.demo(20),null,1000),b=M.frame(M.demo(21),a,2000);
  assert.equal(a.events.length,0);assert.ok(b.rx>0);
  assert.equal(M.frame(M.demo(22),b,9000).rx,null);
  assert.equal(M.frame(M.demo(0),b,3000).rx,null);
});
test('browser preview offers explicit demo without attempting backend calls',()=>{
  const ui=setup(undefined,false);connect(ui);
  assert.match(ui.get('source-state').textContent,/桌面程序/);assert.equal(ui.get('flow-count').textContent,'0');
  ui.get('demo').onclick();assert.equal(ui.get('flow-count').textContent,'5');assert.equal(ui.get('request-log-rows').children.length,0);
});

test('one verified controller auto-connects without a typed address',async()=>{
  const calls=[];const ui=setup(async(command,args)=>{calls.push({command,args});return {...M.demo(0),observed_at:1000,http_status:200};},false,async()=>({controllers:[{controller:'http://127.0.0.1:9191',version:'v1',ready:true}]}));
  await flush();assert.equal(calls.length,1);assert.equal(calls[0].args.controller,'http://127.0.0.1:9191');
  assert.equal(ui.get('controller-address').textContent,'http://127.0.0.1:9191');assert.equal(ui.get('flow-count').textContent,'5');
});
test('multiple controllers require a selection; unverified services never auto-connect',async()=>{
  const calls=[];const ui=setup(async(command,args)=>{calls.push(args);return {...M.demo(0),observed_at:1000};},false,async()=>({controllers:[{controller:'http://127.0.0.1:9191',version:'v1',ready:true},{controller:'http://127.0.0.1:9192',version:'v2',ready:true}]}));
  await flush();assert.equal(calls.length,0);assert.match(ui.get('source-state').textContent,/2 个控制器/);
  ui.get('controller-choice').value='http://127.0.0.1:9192';ui.get('connect-discovered').onclick();await flush();assert.equal(calls[0].controller,'http://127.0.0.1:9192');
  const protectedUi=setup(()=>{throw new Error('must not connect');},false,async()=>({controllers:[{controller:'http://127.0.0.1:9999',ready:false,configured:false}]}));
  await flush();assert.match(protectedUi.get('source-state').textContent,/需要鉴权/);assert.equal(protectedUi.get('flow-count').textContent,'0');
});
test('late discovery cannot override demo or a manually selected controller',async()=>{
  let resolve;const calls=[];const ui=setup(async(command,args)=>{calls.push(args);return {...M.demo(0),observed_at:1000};},false,()=>new Promise(r=>resolve=r));
  ui.get('demo').onclick();resolve({controllers:[{controller:'http://127.0.0.1:9191',ready:true}]});await flush();
  assert.equal(calls.length,0);assert.equal(ui.get('source-state').textContent,'演示数据');
  ui.get('live').onclick();connect(ui);await flush();resolve({controllers:[{controller:'http://127.0.0.1:9191',ready:true}]});await flush();
  assert.equal(calls.length,1);assert.equal(calls[0].controller,'http://127.0.0.1:9090');
});
test('scan failures and empty results retain a usable rescan action',async()=>{
  let scans=0;const ui=setup(()=>{throw new Error('must not connect');},false,async()=>{if(++scans===1)throw new Error('unavailable');return {controllers:[],scanned:4};});
  await flush();assert.match(ui.get('source-state').textContent,/扫描失败/);assert.equal(ui.get('discover').disabled,false);
  await ui.get('discover').onclick();assert.match(ui.get('source-state').textContent,/未发现/);assert.equal(ui.get('discover').disabled,false);
});
