  const specs = [
    ['browser-web','Browser','www.example.com','203.0.113.10',443,'TCP','WireGuard-test',650000,24000],
    ['browser-api','Browser','api.example.com','203.0.113.20',443,'TCP','WireGuard-test',82000,12000],
    ['sync','Cloud Sync','sync.example.net','198.51.100.42',443,'TCP','WireGuard-test',12000,430000],
    ['dns','System','dns.example.net','192.0.2.53',53,'UDP','DIRECT',1200,700],
    ['update','System','updates.example.org','192.0.2.80',443,'TCP','DIRECT',175000,5000]
  ];
  function snapshot(tick) {
    const base = Date.UTC(2026,10,2,2,18,0);
    return {download_total:specs.reduce((sum,s,i)=>sum+800000+i*250000+tick*s[7],0),
      upload_total:specs.reduce((sum,s,i)=>sum+40000+tick*s[8],0),
      connections:specs.map((s,i) => ({id:s[0],start:new Date(base-i*18000).toISOString(),
      metadata:{process:s[1],pid:1200+i,host:s[2],destination_ip:s[3],destination_port:s[4],network:s[5],source_ip:'192.0.2.2',source_port:51000+i},
      chains:[s[6]],rule:s[6]==='DIRECT'?'Direct':'DomainSuffix',
      download:800000+i*250000+tick*s[7],upload:40000+tick*s[8]})),
      events:[]};
  }
module.exports = {snapshot};
