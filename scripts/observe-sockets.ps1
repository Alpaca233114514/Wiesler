$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$observedProcesses = @{}
Get-Process | Where-Object { $_.ProcessName -match '^(codex|mihomo|verge-mihomo|clash|clash-verge)$' } | ForEach-Object {
    $started = $null
    try { $started = $_.StartTime.ToUniversalTime().ToString('o') } catch {}
    $observedProcesses[[int]$_.Id] = @{ name = $_.ProcessName; started = $started }
}
$rows = @(Get-NetTCPConnection | Where-Object { $observedProcesses.ContainsKey([int]$_.OwningProcess) } | ForEach-Object {
    $entry = $observedProcesses[[int]$_.OwningProcess]
    [ordered]@{
        process = $entry.name
        pid = [int]$_.OwningProcess
        process_started_at = $entry.started
        local_ip = $_.LocalAddress
        local_port = [int]$_.LocalPort
        remote_ip = $_.RemoteAddress
        remote_port = [int]$_.RemotePort
        state = [string]$_.State
        role = $(if ($entry.name -eq 'codex') { 'codex' } else { 'proxy_process' })
    }
})
ConvertTo-Json -InputObject @{ process_count = $observedProcesses.Count; observed_at_utc = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); sockets = $rows } -Depth 4 -Compress
