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
        measuredUploads:(frame?.flows || []).filter(f=>Number.isFinite(f.tx)).length,
        dnsPayload:false, interfaceRoute:false, doh:false, webrtc:false},
      policy:{expectProxy:!!policy.expectProxy,uploadBytesPerSecond:Number.isFinite(threshold)&&threshold>0?threshold:null},findings};
  }
  function environment(nav, screen, intl) {
    return {scope:'当前 Wiesler WebView / 页面环境，不代表其他浏览器',
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
      {name:'DNS 路径',state:'unknown',result:!hasSample?'等待采样':dns.length?`${dns.length} 条 DNS 端口连接待核实`:'尚不能判断是否泄漏',
        reason:dns.length?'观察到目标端口 53 的连接，尚无查询内容和实际出口网卡证据。':'当前样本没有 DNS 端口线索，短连接与加密 DNS 仍可能未被观测。',
        action:'核对下方连接的进程与目标解析器，并检查代理的 DNS 配置。'},
      {name:'代理路径',state:bypass.length?'attention':'unknown',
        result:bypass.length?`${bypass.length} 条连接不符合代理预期`:!report.policy.expectProxy?'尚未设置代理预期':!hasSample?'等待采样':partial||report.coverage.unknownRoutes?'部分连接路径待确认':'已观测连接未触发路由差异',
        reason:bypass.length?'你要求已观测连接经代理，但控制器对这些连接报告了 DIRECT。':!report.policy.expectProxy?'分流中的直连可能是正常行为，需要先明确你的预期。':`${report.coverage.unknownRoutes} 条系统连接缺少路由证据；控制器逻辑路由不等于物理出口。`,
        action:bypass.length?'检查对应应用的分流规则；若直连符合你的意图，可取消“所有已观测连接应经代理”。':'仅当你确实要求所有已观测连接走代理时，勾选下方检查条件。'},
      {name:'上传行为',state:uploads.length?'attention':!measured||partial?'unknown':'observed',
        result:uploads.length?`${uploads.length} 条连接上传超过阈值`:!measured?'等待连续流量样本':'已测连接未超过上传阈值',
        reason:uploads.length?'大文件上传、同步或备份都可能触发，需要结合应用用途判断。':`当前 ${total} 条连接中，${measured} 条具备有效上传速率。`,
        action:uploads.length?'先确认下方应用是否正在上传或同步；若不是预期操作，再调查目标地址。':'按实际业务调整阈值，继续观察；未超阈值不等于没有信息外传。'}
    ];
    return {title:!hasSample?'等待连接数据':bypass.length||uploads.length?'发现需要你核对的连接':partial?'采集不完整，暂不能给出完整判断':dns.length?'发现 DNS 连接线索，路径待核实':'连接观察未提供泄漏判断依据',
      detail:!hasSample?'开始实时采集后，这里会解释发现了什么以及下一步该做什么。':`${total} 条已观测连接 · ${bypass.length} 条代理预期差异 · ${uploads.length} 条上传超阈值。${partial?'部分采集失败，以下结果仅覆盖已有数据。':'这些结果用于排查，不是无泄漏认证。'}`,checks};
  }
  function describeEnvironment(value) {
    if(!value)return [];
    return [
      {name:'语言偏好',value:value.languages.length?value.languages.join('、'):'未提供',meaning:'网页可据此了解你偏好的语言；语言与出口地区不同，本身不代表泄漏。'},
      {name:'时区',value:value.timezone||'未提供',meaning:'网页可以读取当前环境的时区。尚未与出口 IP 的时区对比。'},
      {name:'屏幕特征',value:value.screen?`${value.screen.width} × ${value.screen.height} · ${value.screen.pixelDepth} 位色深`:'未提供',meaning:'分辨率与色深可参与设备特征组合，不能单独证明身份或可追踪性。'},
      {name:'浏览器标识',value:value.userAgent||'未提供',meaning:'UA 向网页提供浏览器和系统线索；这是当前 WebView 的标识。'}
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
          `配置网卡：${resolver.configuredInterface}（${resolver.description}）。系统选路：${resolver.routeInterface||'未知'}，下一跳 ${resolver.nextHop||'未知'}。\n${!query?'本轮未测试此服务器':query.error?'解析测试失败：'+query.error:answer?`向此服务器查询 ${query.domain} → ${answer}（${query.elapsedMs} ms）`:'查询未返回 A 记录'}${resolver.routeError?'\n路由读取失败：'+resolver.routeError:''}`,
          query?.error||resolver.routeError?'attention':'observed');
      }
      if(!dns.resolvers.length)add('DNS 配置','未读取到活动网卡的 DNS 服务器','不能据此认定没有使用 DNS。','unknown');
      const physical=dns.resolvers.filter(r=>r.hardware), virtual=dns.resolvers.filter(r=>!r.hardware);
      if(physical.length&&virtual.length)add('DNS 配置并存','物理网卡与虚拟网卡都配置了 DNS',
        `物理网卡：${physical.map(r=>r.server).join('、')}；虚拟网卡：${virtual.map(r=>r.server).join('、')}。存在多条配置路径，需要核对代理的 DNS 劫持/分流设置。上面的查询是本工具主动指定服务器发起，不能证明其他应用已经绕过隧道。`,'attention');
    }else add('DNS 检查',run.dns?.error?'检测失败':'正在读取与解析…',run.dns?.error||'读取活动网卡与 DNS 路由，向最多四个已配置服务器查询 example.com。','unknown');
    for(const [key,label] of [['system','系统路径公网出口'],['proxy','代理路径公网出口']]){
      const item=run[key],v=item?.value;
      add(label,v?.ip|| (item?.error?'未取得结果':'正在探测…'),v?[v.location?.country,v.location?.city,v.location?.isp,v.location?.timezone].filter(Boolean).join(' · ')+(v.location_error?'；地址库查询失败：'+v.location_error:'') :item?.error||'通过 HTTPS 查询 ipify；归属地来自 IPWhois。',v?'observed':'unknown');
    }
    const system=run.system?.value,proxy=run.proxy?.value;
    if(system&&proxy)add('公网出口对比',system.ip===proxy.ip?'两条探测路径出口相同':'两条探测路径出口不同',
      system.ip===proxy.ip?'这两次请求使用相同公网 IP；系统路径可能已被 TUN/VPN 接管。':'系统请求与经代理请求返回不同公网 IP。这是实际出口差异，是否符合预期取决于你的分流设置。',system.ip===proxy.ip?'observed':'attention');
    if(proxy?.location?.timezone){
      try{
        const now=new Date(run.startedAt),zone=proxy.location.timezone;
        const offset=timeZone=>new intl.DateTimeFormat('en',{timeZone,timeZoneName:'shortOffset'}).formatToParts(now).find(p=>p.type==='timeZoneName').value;
        const local=offset(undefined),remote=offset(zone);
        add('时区与代理出口',local===remote?'当前时区偏移与出口地区一致':'当前时区偏移与出口地区不同',`本机 ${intl.DateTimeFormat().resolvedOptions().timeZone}（${local}）；出口地址库 ${zone}（${remote}）。这是可观察的环境差异，不是 DNS 或真实 IP 泄漏。`,local===remote?'observed':'attention');
      }catch(_){add('时区与代理出口','时区对比不可用','出口地址库返回的时区无法识别。','unknown');}
    }
    return cards;
  }
  const api={analyze,environment,summarize,describeEnvironment,activeResults};
  if(typeof module!=='undefined')module.exports=api;
  root.PrivacyModel=api;
})(typeof globalThis!=='undefined'?globalThis:this);
