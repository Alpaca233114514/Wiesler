const {test}=require('node:test');
const assert=require('node:assert/strict');
const P=require('../ui/privacy-model.js');
const M=require('../ui/studio-model.js');
const {snapshot}=require('./snapshot-fixture.cjs');

test('DNS ports are unverified evidence; DIRECT only mismatches an explicit policy',()=>{
  const frame=M.frame(snapshot(0),null,1000);
  const passive=P.analyze(frame);
  assert.equal(passive.findings.length,1);
  assert.equal(passive.findings[0].status,'unverified');
  assert.equal(passive.findings[0].evidence.pid,1203);
  const policy=P.analyze(frame,{expectProxy:true});
  assert.equal(policy.findings.filter(f=>f.status==='policy_mismatch').length,2);
  assert.equal(policy.coverage.interfaceRoute,false);
});
test('system sockets and missing DNS visibility never imply proxy bypass or a clean bill of health',()=>{
  const frame={flows:[{id:'system',port:443,origin:'system',routing:'unknown',tx:null}],error:'HTTP 401'};
  const result=P.analyze(frame,{expectProxy:true,uploadBytesPerSecond:1});
  assert.equal(result.findings.length,0);
  assert.equal(result.coverage.unknownRoutes,1);
  assert.deepEqual(result.errors,['HTTP 401']);
  assert.equal(result.coverage.doh,false);
  assert.equal(P.analyze(null).observedAt,null);
});
test('upload signals require valid differences and preserve connection evidence',()=>{
  const first=M.frame(snapshot(0),null,1000);
  const next=M.frame(snapshot(1),first,2000);
  const policy={uploadBytesPerSecond:100000};
  assert.equal(P.analyze(first,policy).findings.filter(f=>f.kind==='connection').length,0);
  const signal=P.analyze(next,policy).findings.find(f=>f.kind==='connection');
  assert.equal(signal.evidence.connectionId,'sync');
  assert.equal(signal.status,'signal');
  assert.equal(P.analyze(M.frame(snapshot(2),next,10000),policy).findings.filter(f=>f.kind==='connection').length,0);
});
test('environment report is scoped to this WebView and tolerates unavailable APIs',()=>{
  const result=P.environment(undefined,undefined,Intl);
  assert.equal(result.userAgent,null);
  assert.deepEqual(result.languages,[]);
  assert.match(result.scope,/不代表其他浏览器/);
});

test('interpretation distinguishes absent, partial and measured evidence',()=>{
  const empty=P.summarize(P.analyze(null));
  assert.match(empty.title,/等待/);
  assert.ok(empty.checks.every(c=>c.state==='unknown'));
  const first=M.frame(snapshot(0),null,1000);
  assert.match(P.summarize(P.analyze(first)).checks[2].result,/等待连续/);
  const partial=P.summarize(P.analyze({...first,error:'HTTP 401'}));
  assert.match(partial.title,/采集不完整/);
  assert.match(partial.detail,/部分采集失败/);
  const next=M.frame(snapshot(1),first,2000);
  const normal=P.summarize(P.analyze(next,{uploadBytesPerSecond:1024*1024}));
  assert.equal(normal.checks[2].state,'observed');
  assert.equal(normal.checks[0].state,'unknown');
  const flagged=P.summarize(P.analyze(next,{expectProxy:true,uploadBytesPerSecond:100000}));
  assert.match(flagged.title,/需要你核对/);
  assert.match(flagged.checks[1].result,/2 条/);
  assert.match(flagged.checks[2].result,/1 条/);
  assert.ok(flagged.checks.every(c=>c.reason&&c.action));
});

test('environment interpretation preserves measurements without inventing IP mismatch or tracking findings',()=>{
  const rows=P.describeEnvironment({languages:['zh-CN'],timezone:'Asia/Shanghai',screen:{width:1920,height:1080,pixelDepth:24},userAgent:'<example>'});
  assert.equal(rows[0].value,'zh-CN');
  assert.match(rows[1].meaning,/尚未.*对比/);
  assert.match(rows[2].value,/1920 × 1080/);
  assert.equal(rows[3].value,'<example>');
  assert.deepEqual(P.describeEnvironment(null),[]);
});


test('measured DNS results distinguish configured paths and explicit test queries from leakage',()=>{
  const cards=P.activeResults({startedAt:1000,dns:{value:{resolvers:[
    {server:'10.0.0.1',configuredInterface:'WLAN',routeInterface:'WLAN',hardware:true},
    {server:'198.18.0.2',configuredInterface:'Meta',routeInterface:'Meta',hardware:false}
  ],queries:[{server:'10.0.0.1',error:'timeout'}]}},system:{value:{ip:'203.0.113.1'}},proxy:{value:{ip:'203.0.113.1'}}});
  assert.equal(cards[0].state,'attention');assert.match(cards[0].reason,/timeout/);
  assert.match(cards.find(c=>c.name==='DNS 配置并存').reason,/不能证明其他应用/);
  assert.match(cards.find(c=>c.name==='公网出口对比').result,/相同/);
});
