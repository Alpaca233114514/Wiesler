(function(root) {
  'use strict';
  function analyze(frame, policy = {}) {
    const findings = [];
    const threshold = Number(policy.uploadBytesPerSecond);
    for (const flow of frame?.flows || []) {
      const evidence = {connectionId:flow.id, process:flow.app, pid:flow.pid,
        source:flow.source, destination:flow.destination, protocol:flow.protocol,
        origin:flow.origin, route:flow.route, rule:flow.rule, observedAt:frame.time};
      const add = (kind, status, title, detail) => findings.push({kind,status,title,detail,evidence});
      // 端口是线索，不能证明 DNS 内容或物理路径。 / Ports alone prove neither DNS payload nor physical route.
      if (Number(flow.port) === 53) add('network','unverified','DNS 端口连接',
        '目标端口 53；未采集查询正文、出口网卡或解析器转发链，无法确认 DNS 泄漏。');
      if (policy.expectProxy && flow.origin === 'mihomo' && flow.routing === 'direct')
        add('network','policy_mismatch','DIRECT 与预期代理不符',
          '控制器报告 DIRECT，与你设置的“所有已观测连接应经代理”不符；系统 TUN/VPN 路径仍待核实。');
      if (Number.isFinite(threshold) && threshold > 0 && Number.isFinite(flow.tx) && flow.tx > threshold)
        add('connection','signal','上传速率超过阈值',
          `当前连接上传 ${Math.round(flow.tx)} B/s，阈值 ${threshold} B/s；这是调查线索，不代表信息外泄。`);
    }
    return {schemaVersion:1, observedAt:frame?.time ?? null, source:frame?.source ?? 'none',
      errors:[frame?.error,frame?.systemError].filter(Boolean),
      coverage:{connections:frame?.flows?.length ?? 0, unknownRoutes:(frame?.flows || []).filter(f=>f.routing==='unknown').length,
        dnsPayload:false, interfaceRoute:false, doh:false, webrtc:false},
      policy:{expectProxy:!!policy.expectProxy,uploadBytesPerSecond:Number.isFinite(threshold)&&threshold>0?threshold:null},findings};
  }
  function environment(nav, screen, intl) {
    return {scope:'当前 Wiesler WebView / 页面环境，不代表其他浏览器',
      userAgent:nav?.userAgent || null,languages:Array.from(nav?.languages || []),
      timezone:intl.DateTimeFormat().resolvedOptions().timeZone || null,
      screen:screen?{width:screen.width,height:screen.height,pixelDepth:screen.pixelDepth}:null};
  }
  const api={analyze,environment};
  if(typeof module!=='undefined')module.exports=api;
  root.PrivacyModel=api;
})(typeof globalThis!=='undefined'?globalThis:this);
