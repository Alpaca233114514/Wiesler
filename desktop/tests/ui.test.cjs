const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
function setup(invoke) {
  const elements = new Map();
  class Element {
    constructor() { this.value=''; this.textContent=''; this.children=[]; this.dataset={}; this.classList={toggle(){}}; }
    append(...nodes){this.children.push(...nodes);}
    replaceChildren(...nodes){this.children=nodes;}
    setAttribute(key,value){this[key]=value;} showModal(){} close(){}
  }
  const get = id => {if(!elements.has(id)) elements.set(id,new Element());return elements.get(id);};
  let next;
  const context=vm.createContext({window:{__TAURI__:{core:{invoke}}},document:{getElementById:get,createElement:()=>new Element(),querySelectorAll:()=>[]},setTimeout:fn=>{next=fn;return 1;},clearTimeout:()=>{next=null;}});
  vm.runInContext(fs.readFileSync('desktop/ui/app.js','utf8'),context);
  get('controller').value='http://127.0.0.1:9090';
  return {get,context,tick:async()=>{const fn=next;next=null;await fn?.();},run:code=>vm.runInContext(code,context)};
}
const sample=(time,download,upload=0)=>({http_status:200,observed_at:time,download_total:download,upload_total:upload,connections:[]});
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('rate rebaselines across errors and counter resets',async()=>{
  const queue=[sample(1000,100),sample(2000,1124),new Error('network_error'),sample(4000,5000),sample(5000,1)];
  const ui=setup(async()=>{const next=queue.shift();if(next instanceof Error)throw next;return next;});
  ui.run('start()');await flush(); assert.equal(ui.get('rate').textContent,'等待差分');
  await ui.tick();assert.equal(ui.get('rate').textContent,'1.00 KB/s');
  await ui.tick();assert.equal(ui.get('active').textContent,'—');assert.equal(ui.get('health').textContent,'采集异常');
  await ui.tick();assert.equal(ui.get('rate').textContent,'等待差分');
  await ui.tick();assert.equal(ui.get('rate').textContent,'等待差分');
});
test('paused or superseded requests cannot replace current state',async()=>{
  const resolvers=[];const ui=setup(()=>new Promise(resolve=>resolvers.push(resolve)));
  ui.run('start()');ui.get('pause').onclick();resolvers.shift()(sample(1000,900));await flush();
  assert.equal(ui.get('health').textContent,'已暂停');
  ui.run('start()');ui.run('start()');resolvers[1](sample(3000,300));await flush();resolvers[0](sample(2000,9999));await flush();
  assert.equal(ui.get('download-total').textContent,'控制器累计 300 B');
});
test('changed database ignores an outstanding session response',async()=>{
  let resolve;const ui=setup(()=>new Promise(r=>resolve=r));ui.get('database').value='first.sqlite3';
  const pending=ui.get('database-form').onsubmit({preventDefault(){}});
  ui.get('database').value='second.sqlite3';ui.get('database').oninput();resolve([{session_id:'old',started_at_utc:1000,event_count:1}]);await pending;
  assert.equal(ui.get('session').children.length,0);assert.equal(ui.get('diagnose').disabled,true);
});
test('connection values are rendered as text and protocol search works',async()=>{
  const data=sample(1000,0);data.connections=[{id:'test',download:0,upload:0,chains:['DIRECT'],metadata:{process:'<img src=x onerror=alert(1)>',network:'tcp',host:'example.org',destination_port:443}}];
  const ui=setup(async()=>data);ui.run('start()');await flush();
  assert.equal(ui.get('rows').children[0].children[0].children[0].textContent,'<img src=x onerror=alert(1)>');
  ui.get('protocol').value='UDP';ui.get('protocol').onchange();assert.equal(ui.get('count').textContent,' / 0 条');
});

test('paused snapshot with no search matches remains a valid snapshot',async()=>{
  const ui=setup(async()=>sample(1000,100));ui.run('start()');await flush();
  ui.get('pause').onclick();ui.get('search').value='no-match-xyz-123';ui.get('search').oninput();
  assert.equal(ui.get('rows').children[0].children[0].textContent,'没有匹配的连接');
  assert.equal(ui.get('health').textContent,'已暂停');
});

test('latest traffic sample aligns with now before history fills',async()=>{
  const queue=[sample(1000,0),sample(2000,1024),sample(3000,2048)];
  const ui=setup(async()=>queue.shift());ui.run('start()');await flush();await ui.tick();await ui.tick();
  assert.match(ui.get('download').d,/L800,/);
  assert.match(ui.get('upload').d,/L800,/);
});

test('request log keeps success, forbidden, and timeout distinct after recovery',async()=>{
  const queue=[sample(1000,0),{message:'authentication_failed (HTTP 403)',http_status:403},new Error('timeout'),sample(4000,1)];
  const ui=setup(async()=>{const item=queue.shift();if(!item.connections)throw item;return item;});
  ui.run('start()');await flush();await ui.tick();await ui.tick();await ui.tick();
  const rows=ui.get('request-log-rows').children;
  assert.equal(rows.length,4);
  assert.equal(rows[0].children[2].textContent,'HTTP 200');
  assert.equal(rows[0].children[3].textContent,'成功');
  assert.equal(rows[1].children[2].textContent,'未取得状态码');
  assert.equal(rows[1].children[4].textContent,'timeout');
  assert.equal(rows[2].children[2].textContent,'HTTP 403');
  assert.equal(rows[2].children[3].textContent,'失败');
  assert.equal(rows[2].children[2].className,'http-error');
});

test('request log is bounded and ignores superseded responses',async()=>{
  let resolve;const ui=setup(()=>new Promise(r=>resolve=r));
  ui.run('start()');ui.get('pause').onclick();resolve(sample(1000,1));await flush();
  assert.equal(ui.get('request-log-rows').children.length,0);
  ui.run('for(let i=0;i<105;i++)appendRequestLog(200,null)');
  assert.equal(ui.get('request-log-rows').children.length,100);
  ui.run('appendRequestLog(null,"<img src=x>")');
  assert.equal(ui.get('request-log-rows').children[0].children[4].textContent,'<img src=x>');
});
