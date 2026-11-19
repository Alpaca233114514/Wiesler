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
        '目标端口 53，具体查询域名与出口网卡请查看上方本地 DNS 检查。');
      if (policy.expectProxy && flow.origin === 'mihomo' && flow.routing === 'direct')
        add('network','policy_mismatch','DIRECT 与预期代理不符',
          '控制器报告该连接走 DIRECT，与当前代理预期不一致。');
      if (Number.isFinite(threshold) && threshold > 0 && Number.isFinite(flow.tx) && flow.tx > threshold)
        add('connection','signal','上传速率超过阈值',
          `当前上传 ${Math.round(flow.tx)} B/s，已超过阈值 ${threshold} B/s。`);
    }
    return {schemaVersion:1, observedAt:frame?.time ?? null, source:frame?.source ?? 'none',
      errors:[frame?.error,frame?.systemError].filter(Boolean),
      coverage:{connections:frame?.flows?.length ?? 0, unknownRoutes:(frame?.flows || []).filter(f=>f.routing==='unknown').length,
        measuredUploads:(frame?.flows || []).filter(f=>Number.isFinite(f.tx)).length,
        dnsPayload:false, interfaceRoute:false, doh:false, webrtc:false},
      policy:{expectProxy:!!policy.expectProxy,uploadBytesPerSecond:Number.isFinite(threshold)&&threshold>0?threshold:null},findings};
  }
  function environment(nav, screen, intl) {
    return {scope:'当前 Wiesler WebView 环境，不代表其他浏览器',
      userAgent:nav?.userAgent || null,languages:Array.from(nav?.languages || []),
      timezone:intl.DateTimeFormat().resolvedOptions().timeZone || null,
      screen:screen?{width:screen.width,height:screen.height,pixelDepth:screen.pixelDepth}:null};
  }
  function summarize(report) {
    const dns=report.findings.filter(f=>f.title==='DNS 端口连接');
    const bypass=report.findings.filter(f=>f.status==='policy_mismatch');
    const uploads=report.findings.filter(f=>f.kind==='connection');
    const hasSample=report.observedAt!=null, partial=report.errors.length>0;
    const total=report.coverage.connections, measured=report.coverage.measuredUploads;
    const checks=[
      {name:'53 端口连接',state:'unknown',result:!hasSample?'等待连接采样':dns.length?`${dns.length} 条连接使用 53 端口`:'未发现 53 端口连接',
        reason:'连接快照仅包含端口信息，详细查询路径见上方本地 DNS 检查。',
        action:'使用上方「开始本地检查」查看域名与网卡出口。'},
      {name:'代理路径',state:bypass.length?'attention':'unknown',
        result:bypass.length?`${bypass.length} 条连接不符合代理预期`:!report.policy.expectProxy?'未开启全代理核查':!hasSample?'等待采样':partial||report.coverage.unknownRoutes?'部分连接路径待确认':'已观测连接均符合预期',
        reason:bypass.length?'控制器对这些连接报告了 DIRECT 直连。':!report.policy.expectProxy?'当前未要求全部连接走代理。':`其中 ${report.coverage.unknownRoutes} 条系统连接缺少路由标记。`,
        action:bypass.length?'核对对应应用的分流规则，或关闭「预期所有连接经代理转发」。':'需要检查全代理时可勾选「预期所有连接经代理转发」。'},
      {name:'上传速率',state:uploads.length?'attention':!measured||partial?'unknown':'observed',
        result:uploads.length?`${uploads.length} 条连接上传超过阈值`:!measured?'等待连续流量样本':'均在上传阈值内',
        reason:uploads.length?'检测到高于阈值的持续上传流量。':`当前 ${total} 条连接中 ${measured} 条有实时速率计数。`,
        action:uploads.length?'确认对应应用是否正在同步或上传。':'可按需调整上传阈值。'}
    ];
    return {title:!hasSample?'等待连接数据':bypass.length||uploads.length?'发现需要你核对的连接':partial?'采集不完整':dns.length?'发现 53 端口连接':'连接路径无异常',
      detail:!hasSample?'连接采集开始后显示汇总。':`${total} 条连接 · ${bypass.length} 条直连差异 · ${uploads.length} 条上传超阈值${partial?'（部分采集失败）':''}`,checks};
  }
  function describeEnvironment(value) {
    if(!value)return [];
    return [
      {name:'语言偏好',value:value.languages.length?value.languages.join('、'):'未提供',meaning:'页面可读取的首选语言列表。'},
      {name:'时区',value:value.timezone||'未提供',meaning:'当前系统时区（尚未与出口 IP 时区对比）。'},
      {name:'屏幕特征',value:value.screen?`${value.screen.width} × ${value.screen.height} · ${value.screen.pixelDepth} 位色深`:'未提供',meaning:'页面可见的分辨率与色深。'},
      {name:'浏览器标识',value:value.userAgent||'未提供',meaning:'当前 WebView 的 User-Agent。'}
    ];
  }
  function activeResults(run, intl=Intl) {
    const cards=[];
    const add=(name,result,reason,state='observed')=>cards.push({name,result,reason,state});
    const dns=run.dns?.value;
    if(dns){
      for(const resolver of dns.resolvers){
        const query=dns.queries.find(q=>q.server===resolver.server);
        const answer=query?.addresses?.join('、');
        add('DNS 服务器',resolver.server,
          `配置网卡：${resolver.configuredInterface}（${resolver.description}）。系统选路：${resolver.routeInterface||'未知'}，下一跳 ${resolver.nextHop||'未知'}。\n${!query?'本轮未测试此服务器':query.error?'解析测试失败：'+query.error:answer?`查询 ${query.domain} → ${answer}（${query.elapsedMs} ms）`:'未返回 A 记录'}${resolver.routeError?'\n路由读取失败：'+resolver.routeError:''}`,
          query?.error||resolver.routeError?'attention':'observed');
      }
      if(!dns.resolvers.length)add('DNS 配置','未读取到活动网卡的 DNS 服务器','活动网卡未配置 DNS 地址。','unknown');
      const physical=dns.resolvers.filter(r=>r.hardware), virtual=dns.resolvers.filter(r=>!r.hardware);
      if(physical.length&&virtual.length)add('DNS 配置并存','物理网卡与虚拟网卡均配置了 DNS',
        `物理网卡：${physical.map(r=>r.server).join('、')}；虚拟网卡：${virtual.map(r=>r.server).join('、')}。建议核对代理的 DNS 劫持或分流设置。`,'attention');
    }else add('DNS 检查',run.dns?.error?'检测失败':'正在读取与解析…',run.dns?.error||'读取活动网卡 DNS 配置并测试解析 example.com。','unknown');
    for(const [key,label] of [['system','系统路径公网出口'],['proxy','代理路径公网出口']]){
      const item=run[key],v=item?.value;
      add(label,v?.ip|| (item?.error?'未取得结果':'正在探测…'),v?[v.location?.country,v.location?.city,v.location?.isp,v.location?.timezone].filter(Boolean).join(' · ')+(v.location_error?'；归属地查询失败：'+v.location_error:'') :item?.error||'通过 HTTPS 查询公网出口与归属地。',v?'observed':'unknown');
    }
    const system=run.system?.value,proxy=run.proxy?.value;
    if(system&&proxy)add('公网出口对比',system.ip===proxy.ip?'两条探测路径出口相同':'两条探测路径出口不同',
      system.ip===proxy.ip?'系统路径与代理路径返回相同公网 IP，流量可能已由 TUN/VPN 接管。':'系统路径与代理路径返回不同公网 IP，当前处于分流状态。',system.ip===proxy.ip?'observed':'attention');
    if(proxy?.location?.timezone){
      try{
        const now=new Date(run.startedAt),zone=proxy.location.timezone;
        const offset=timeZone=>new intl.DateTimeFormat('en',{timeZone,timeZoneName:'shortOffset'}).formatToParts(now).find(p=>p.type==='timeZoneName').value;
        const local=offset(undefined),remote=offset(zone);
        add('时区与代理出口',local===remote?'时区与出口一致':'时区与出口不同',`本机 ${intl.DateTimeFormat().resolvedOptions().timeZone}（${local}）；出口 ${zone}（${remote}）。`,local===remote?'observed':'attention');
      }catch(_){add('时区与代理出口','时区对比不可用','出口地址库返回的时区无法识别。','unknown');}
    }
    return cards;
  }
  const api={analyze,environment,summarize,describeEnvironment,activeResults};
  if(typeof module!=='undefined')module.exports=api;
  root.PrivacyModel=api;
})(typeof globalThis!=='undefined'?globalThis:this);
