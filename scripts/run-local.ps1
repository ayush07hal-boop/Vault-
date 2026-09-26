# Runs a full Vault cluster on this machine WITHOUT Docker or PostgreSQL:
#   4 storage nodes (ports 9091-9094, data under .\data\node-N) + the API on :8080 (in-memory H2, "dev" profile).
# Usage:  .\scripts\run-local.ps1        (Ctrl+C stops the API; run .\scripts\stop-local.ps1 to stop the nodes)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Load settings from .env (KEY=VALUE per line; # comments and empty values are ignored).
# Variables already set in your shell take precedence over the file.
$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and $line -notmatch '^\s*#') {
            $key = $Matches[1]
            $value = $Matches[2].Trim('"').Trim("'")
            if ($value -and -not (Test-Path "env:$key")) { Set-Item "env:$key" $value }
        }
    }
    Write-Host "loaded settings from .env"
} else {
    Write-Host "no .env found (copy .env.example to .env to configure Google sign-in and admin access)"
}

$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { "mvn" } else { "$env:USERPROFILE\tools\apache-maven-3.9.9\bin\mvn.cmd" }
& $mvn -q -B -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "build failed" }

$nodeJar = (Get-ChildItem "$root\storage-node\target\storage-node-*-shaded.jar" | Select-Object -First 1).FullName
$apiJar = (Get-ChildItem "$root\vault-api\target\vault-api-*.jar" | Where-Object { $_.Name -notlike "*original*" } | Select-Object -First 1).FullName

New-Item -ItemType Directory -Force "$root\data\logs" | Out-Null
$pids = @()
1..4 | ForEach-Object {
    $env:NODE_ID = "node-$_"
    $env:NODE_PORT = "909$_"
    $env:DATA_DIR = "$root\data\node-$_"
    $env:NODE_CAPACITY_BYTES = "1073741824"   # 1 GiB per node for local runs
    $p = Start-Process java -ArgumentList "-jar", "`"$nodeJar`"" -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$root\data\logs\node-$_.out.log" -RedirectStandardError "$root\data\logs\node-$_.err.log"
    $pids += $p.Id
}
$pids | Set-Content "$root\data\nodes.pids"
Write-Host "started storage nodes (pids: $($pids -join ', '))"

if ($env:VAULT_DB_URL) {
    # PostgreSQL configured in .env: metadata survives restarts (no "dev" profile, settings come from .env)
    Write-Host "starting API on http://localhost:8080 (metadata in PostgreSQL) ..."
    & java -jar $apiJar
} else {
    Write-Host "starting API on http://localhost:8080 (in-memory database: data is lost on restart) ..."
    & java "-Dspring.profiles.active=dev" -jar $apiJar
}
