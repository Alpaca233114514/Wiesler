const {test}=require('node:test');
const assert=require('node:assert/strict');
const P=require('../ui/privacy-model.js');
const M=require('../ui/studio-model.js');

test('DNS ports are unverified evidence; DIRECT only mismatches an explicit policy',()=>{
  const frame=M.frame(M.demo(0),null,1000);
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
  const first=M.frame(M.demo(0),null,1000);
  const next=M.frame(M.demo(1),first,2000);
  const policy={uploadBytesPerSecond:100000};
  assert.equal(P.analyze(first,policy).findings.filter(f=>f.kind==='connection').length,0);
  const signal=P.analyze(next,policy).findings.find(f=>f.kind==='connection');
  assert.equal(signal.evidence.connectionId,'sync');
  assert.equal(signal.status,'signal');
  assert.equal(P.analyze(M.frame(M.demo(2),next,10000),policy).findings.filter(f=>f.kind==='connection').length,0);
});
test('environment report is scoped to this WebView and tolerates unavailable APIs',()=>{
  const result=P.environment(undefined,undefined,Intl);
  assert.equal(result.userAgent,null);
  assert.deepEqual(result.languages,[]);
  assert.match(result.scope,/不代表其他浏览器/);
});
