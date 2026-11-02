$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$observedProcesses = @{}
Get-Process | ForEach-Object {
    $started = $null
    try { $started = $_.StartTime.ToUniversalTime().ToString('o') } catch {}
    $observedProcesses[[int]$_.Id] = @{ name = $_.ProcessName; started = $started }
}
$rows = @(Get-NetTCPConnection | Where-Object { $_.State -eq 'Established' } | ForEach-Object {
    $entry = $observedProcesses[[int]$_.OwningProcess]
    [ordered]@{
        process = $(if ($entry) { $entry.name } else { 'unknown' })
        pid = [int]$_.OwningProcess
        process_started_at = $(if ($entry) { $entry.started } else { $null })
        local_ip = $_.LocalAddress
        local_port = [int]$_.LocalPort
        remote_ip = $_.RemoteAddress
        remote_port = [int]$_.RemotePort
        state = [string]$_.State
        role = 'system_connection'
    }
})
$processNames = @{}
foreach ($processId in $observedProcesses.Keys) { $processNames[[string]$processId] = $observedProcesses[$processId].name }
$udpOwners = @(Get-NetUDPEndpoint | ForEach-Object {
    $entry = $observedProcesses[[int]$_.OwningProcess]
    [ordered]@{ process = $(if ($entry) { $entry.name } else { 'unknown' }); pid = [int]$_.OwningProcess; local_ip = $_.LocalAddress; local_port = [int]$_.LocalPort }
})
ConvertTo-Json -InputObject @{ process_count = $observedProcesses.Count; processes = $processNames; udp_owners = $udpOwners; observed_at_utc = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); sockets = $rows } -Depth 4 -Compress
