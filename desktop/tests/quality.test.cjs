const { test } = require('node:test');
const assert = require('node:assert/strict');
const Q = require('../ui/quality-model.js');

test('dynamic weight normalization ignores unprobed dimensions without fabricating scores', () => {
  const result = Q.evaluate({
    nowMs: 1000,
    link: { connected: true, validated: true, transportLabel: '有线以太网' },
    pingTarget: '1.1.1.1',
    pingSent: 0
  });
  assert.equal(result.measuredDimensions, 1);
  assert.equal(result.totalApplicableDimensions, 3);
  assert.equal(result.overallScore, 90);
  assert.equal(result.grade.badge, 'S');
  assert.equal(result.dimensions.find(d => d.id === 'latency_stability').available, false);
  assert.equal(result.dimensions.find(d => d.id === 'reachability_dns').available, false);
  assert.equal(result.dimensions.find(d => d.id === 'proxy_health').available, false);
});

test('hard bottleneck caps clamp high averages on severe loss or RF pollution', () => {
  const highLoss = Q.evaluate({
    nowMs: 2000,
    link: { connected: true, validated: true, transportLabel: 'Wi-Fi' },
    pingSent: 20,
    pingReceived: 12,
    lossPercent: 40,
    averageRttMs: 18,
    recentPings: [
      { outcome: 'reply', rtt_ms: 17 },
      { outcome: 'reply', rtt_ms: 19 }
    ],
    httpsProbe: { ok: true, status_code: 204, latency_ms: 60 },
    systemEgress: { ip: '203.0.113.10' }
  });
  assert.ok(highLoss.overallScore <= 39);
  assert.equal(highLoss.scenarios.find(s => s.id === 'voice').status, 'degraded');

  const severeRf = Q.evaluate({
    nowMs: 3000,
    link: { connected: true, validated: true, transportLabel: 'Wi-Fi' },
    pingSent: 10,
    pingReceived: 10,
    lossPercent: 0,
    averageRttMs: 20,
    httpsProbe: { ok: true, status_code: 204, latency_ms: 80 },
    rfReport: {
      severity: 'severe',
      severity_label: '重度污染',
      score: 88,
      slots: [{ phone_id: 0, band: 'n78', in_service: true, rsrp_dbm: -84, sinr_db: -4, fresh_frames: 5 }],
      attribution: {
        cause: 'CELLULAR_RF_POLLUTION',
        title: '手机蜂窝空口重度污染',
        detail: '剪刀差与低 SINR 并发',
        suggestion: '更换摆放位置或切换频段'
      }
    }
  });
  assert.ok(severeRf.overallScore <= 42);
  assert.equal(severeRf.headline, '手机蜂窝空口重度污染');
  assert.equal(severeRf.advice, '更换摆放位置或切换频段');
});

test('offline link produces score 0 and degraded scenarios', () => {
  const offline = Q.evaluate({ nowMs: 4000, link: { connected: false } });
  assert.equal(offline.overallScore, 0);
  assert.equal(offline.grade.id, 'OFFLINE');
  assert.ok(offline.scenarios.every(s => s.status === 'degraded'));
});

test('jitter, proxy dimension, performance profiles, and sliding history buffer work cleanly', () => {
  const jitter = Q.computeJitterMs([
    { outcome: 'reply', rtt_ms: 20 },
    { outcome: 'lost', rtt_ms: null },
    { outcome: 'reply', rtt_ms: 30 },
    { outcome: 'reply', rtt_ms: 24 }
  ]);
  assert.equal(jitter, 8);

  const healthy = Q.evaluate({
    nowMs: 5000,
    link: { connected: true, validated: true, transportLabel: 'Wi-Fi' },
    pingSent: 10,
    pingReceived: 10,
    lossPercent: 0,
    averageRttMs: 22,
    recentPings: [
      { outcome: 'reply', rtt_ms: 21 },
      { outcome: 'reply', rtt_ms: 23 }
    ],
    dnsInspection: { resolvers: [{ server: '198.18.0.2' }], queries: [{ elapsedMs: 12, addresses: ['93.184.216.34'] }] },
    httpsProbe: { ok: true, status_code: 204, latency_ms: 110 },
    systemEgress: { ip: '203.0.113.1' },
    proxyEgress: { ip: '198.51.100.2', location: { country: 'Japan', city: 'Tokyo' } },
    controllerEnabled: true,
    controllerHealthy: true,
    activeFlowCount: 14,
    proxyFlowCount: 6,
    recentRequestLogs: [{ result: '成功' }, { result: '成功' }]
  });
  assert.equal(healthy.measuredDimensions, 4);
  assert.equal(healthy.totalApplicableDimensions, 4);
  assert.ok(healthy.overallScore >= 90);
  assert.equal(healthy.dimensions.find(d => d.id === 'proxy_health').statusText, '已分流');
  assert.equal(healthy.scenarios.find(s => s.id === 'game').status, 'optimal');
  assert.equal(healthy.scenarios.find(s => s.id === 'proxy').status, 'optimal');

  const buf = Q.createHistoryBuffer(3);
  buf.record({ evaluatedAtMs: 1000, overallScore: 80 }, 20, 0);
  buf.record({ evaluatedAtMs: 1500, overallScore: 82 }, 22, 0); // throttled (<900ms)
  buf.record({ evaluatedAtMs: 2000, overallScore: 85 }, 18, 0);
  buf.record({ evaluatedAtMs: 3000, overallScore: 88 }, 16, 0);
  buf.record({ evaluatedAtMs: 4000, overallScore: 91 }, 15, 0);
  assert.deepEqual(buf.snapshot().map(p => p.score), [85, 88, 91]);

  assert.equal(Q.resolvePerformanceMode('eco').activeTickMs, 3000);
  assert.equal(Q.resolvePerformanceMode('balanced').activeTickMs, 1000);
  assert.equal(Q.resolvePerformanceMode('high').activeTickMs, 500);
  assert.equal(Q.resolvePerformanceMode('unknown').code, 'balanced');
});
