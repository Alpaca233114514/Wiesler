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
function setup(invoke){
  class Element{
    constructor(){this.value='';this.textContent='';this.children=[];this.dataset={};this.attrs={};this.classList={toggle(){}};}
    append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}
    setAttribute(k,v){this.attrs[k]=v;}
  }
  const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  const timers=new Map();let timerId=0;
  const window={StudioModel:M,innerWidth:1360,addEventListener(){},matchMedia:()=>({matches:false}),__TAURI__:invoke?{core:{invoke}}:null};
  const context=vm.createContext({window,document:{hidden:false,getElementById:get,createElement:()=>new Element(),createElementNS:()=>new Element(),querySelectorAll:()=>[],addEventListener(){}},setTimeout:fn=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id)});
  vm.runInContext(fs.readFileSync('desktop/ui/studio.js','utf8'),context);
  const content=el=>[el.textContent,...el.children.map(content)].join(' ');
  return {get,content,tick:async()=>{const [id,fn]=timers.entries().next().value||[];if(fn){timers.delete(id);await fn();}}};
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
