# Stops the storage nodes started by run-local.ps1 (identified by the gRPC ports they listen on, since on
# Windows `java` on PATH may be a launcher shim that spawns the real JVM as a child process).
# Usage: .\scripts\stop-local.ps1            stop all four nodes
#        .\scripts\stop-local.ps1 -Node 2    stop only node-2 (simulate a crash)
param([int]$Node = 0)
$ports = if ($Node -gt 0) { @(9090 + $Node) } else { 9091..9094 }
foreach ($port in $ports) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue; Write-Host "stopped process listening on :$port" }
}
