param(
    [int] $BackendPort = 18000,
    [int] $WebPort = 18080
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Python = Join-Path $Root ".venv\Scripts\python.exe"
$MavenCandidates = @(
    $env:MAVEN_CMD,
    (Join-Path $Root ".dev-tools\apache-maven-3.9.9\bin\mvn.cmd"),
    (Join-Path (Join-Path $env:TEMP "rca-maven") "apache-maven-3.9.9\bin\mvn.cmd")
) | Where-Object { $_ -and (Test-Path $_) }
$Maven = $MavenCandidates | Select-Object -First 1
$MavenRepo = if ($env:RCA_MAVEN_REPO) { $env:RCA_MAVEN_REPO } else { Join-Path $env:TEMP "rca-maven-repo" }
$BackendUrl = "http://127.0.0.1:$BackendPort"
$WebUrl = "http://127.0.0.1:$WebPort"
$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("rca-smoke-" + [System.Guid]::NewGuid().ToString("N"))
$BackendJob = $null
$WebJob = $null
$Success = $false

function Write-Step {
    param([string] $Message)
    Write-Host "[integration-smoke] $Message"
}

function Wait-Http {
    param(
        [string] $Url,
        [string] $Name
    )
    for ($i = 0; $i -lt 45; $i++) {
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2 | Out-Null
            Write-Step "$Name is ready"
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "$Name did not become ready: $Url"
}

function Invoke-Maven {
    param(
        [string] $Maven,
        [string[]] $Arguments
    )
    New-Item -ItemType Directory -Force -Path $MavenRepo | Out-Null
    & $Maven "-Dmaven.repo.local=$MavenRepo" @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven command failed: $Maven $($Arguments -join ' ')"
    }
}

if (-not (Test-Path $Python)) {
    throw "Python virtual environment is missing: $Python"
}
if (-not $Maven) {
    throw "Maven is missing. Run scripts/windows-dev-check.ps1 -BootstrapMaven first."
}

New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

try {
    Write-Step "Packaging web console"
    Push-Location (Join-Path $Root "web-console")
    try {
        Invoke-Maven $Maven @("-q", "package", "-DskipTests")
    } finally {
        Pop-Location
    }

    $DbPath = (Join-Path $TempDir "rca-smoke.db") -replace "\\", "/"
    $BackendLog = Join-Path $TempDir "backend.log"
    $WebLog = Join-Path $TempDir "web-console.log"

    Write-Step "Starting backend on $BackendUrl"
    $BackendJob = Start-Job -ArgumentList $Root, $Python, $BackendPort, $DbPath, $BackendLog -ScriptBlock {
        param($Root, $Python, $BackendPort, $DbPath, $BackendLog)
        Set-Location $Root
        $env:RCA_DATABASE_URL = "sqlite:///$DbPath"
        $env:RCA_AUTO_CREATE_TABLES = "true"
        $env:RCA_LLM_PROVIDER = "disabled"
        & $Python -m uvicorn backend.app.main:app --host 127.0.0.1 --port $BackendPort *> $BackendLog
    }
    Wait-Http "$BackendUrl/health/ready" "backend"

    Write-Step "Starting web console on $WebUrl"
    $WarPath = Join-Path $Root "web-console\target\cluster-infra-rca-web-console-0.1.0.war"
    $WebJob = Start-Job -ArgumentList $WarPath, $WebPort, $BackendUrl, $WebLog -ScriptBlock {
        param($WarPath, $WebPort, $BackendUrl, $WebLog)
        $env:RCA_API_BASE_URL = $BackendUrl
        $env:RCA_PUBLIC_API_BASE_URL = $BackendUrl
        & java -jar $WarPath "--server.port=$WebPort" *> $WebLog
    }
    Wait-Http "$WebUrl/" "web console"

    $Page = Invoke-WebRequest -Uri "$WebUrl/" -UseBasicParsing
    if ($Page.Content -notlike "*rca-console-root*") {
        throw "console page did not include React mount root"
    }
    if (-not $Page.Headers["Content-Security-Policy"]) {
        throw "console response is missing Content-Security-Policy"
    }
    if ($Page.Headers["X-Frame-Options"] -ne "DENY") {
        throw "console response is missing X-Frame-Options: DENY"
    }

    $ProxyHealth = Invoke-RestMethod -Uri "$WebUrl/console-api/health"
    if ($ProxyHealth.status -ne "ok") {
        throw "proxy health check failed"
    }

    $ProxyReady = Invoke-RestMethod -Uri "$WebUrl/console-api/health/ready"
    if ($ProxyReady.database -ne "reachable") {
        throw "proxy readiness check failed"
    }

    $Cluster = Invoke-RestMethod `
        -Method Post `
        -Uri "$WebUrl/console-api/api/clusters" `
        -Headers @{ "X-Admin-Token" = "dev-admin-approval-token" } `
        -ContentType "application/json" `
        -Body '{"name":"smoke-cluster","environment":"dev"}'
    if ($Cluster.name -ne "smoke-cluster" -or -not $Cluster.cluster_id) {
        throw "cluster creation through web proxy failed"
    }

    $Success = $true
    Write-Step "Integration smoke check passed"
} finally {
    if ($WebJob) {
        Stop-Job $WebJob -ErrorAction SilentlyContinue
        if (-not $Success) {
            Receive-Job $WebJob -ErrorAction SilentlyContinue
        }
        Remove-Job $WebJob -Force -ErrorAction SilentlyContinue
    }
    if ($BackendJob) {
        Stop-Job $BackendJob -ErrorAction SilentlyContinue
        if (-not $Success) {
            Receive-Job $BackendJob -ErrorAction SilentlyContinue
        }
        Remove-Job $BackendJob -Force -ErrorAction SilentlyContinue
    }
    if (-not $Success -and (Test-Path $TempDir)) {
        Get-ChildItem $TempDir -Filter *.log -ErrorAction SilentlyContinue | ForEach-Object {
            Write-Step "Log tail: $($_.Name)"
            Get-Content $_.FullName -Tail 120 -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path $TempDir) {
        Remove-Item -LiteralPath $TempDir -Recurse -Force
    }
}
