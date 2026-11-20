$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$adapters = @(Get-NetAdapter -IncludeHidden | Where-Object Status -eq 'Up')
$dnsConfigs = @(Get-DnsClientServerAddress)
$resolvers = @()
foreach ($adapter in $adapters) {
    foreach ($config in @($dnsConfigs | Where-Object InterfaceIndex -eq $adapter.ifIndex)) {
        foreach ($server in $config.ServerAddresses) {
            $routeName = $null; $nextHop = $null; $routeError = $null
            try {
                $route = Find-NetRoute -RemoteIPAddress $server | Where-Object { $_.DestinationPrefix } | Select-Object -First 1
                $routeName = $route.InterfaceAlias; $nextHop = $route.NextHop
            } catch { $routeError = $_.Exception.Message }
            $resolvers += [ordered]@{server=$server; configuredInterface=$adapter.Name; description=$adapter.InterfaceDescription; hardware=[bool]$adapter.HardwareInterface; routeInterface=$routeName; nextHop=$nextHop; routeError=$routeError}
        }
    }
}
$queries = @()
foreach ($server in @($resolvers.server | Select-Object -Unique -First 4)) {
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $answers = @(Resolve-DnsName -Name 'example.com' -Type A -Server $server -DnsOnly -NoHostsFile -QuickTimeout | Where-Object IPAddress | Select-Object -ExpandProperty IPAddress)
        $queries += [ordered]@{server=$server; domain='example.com'; addresses=$answers; elapsedMs=$timer.ElapsedMilliseconds; error=$null}
    } catch {
        $queries += [ordered]@{server=$server; domain='example.com'; addresses=@(); elapsedMs=$timer.ElapsedMilliseconds; error=$_.Exception.Message}
    }
}
[ordered]@{observedAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); resolvers=@($resolvers); queries=@($queries)} | ConvertTo-Json -Depth 6 -Compress
