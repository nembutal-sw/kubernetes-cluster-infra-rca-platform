param(
    [switch] $BootstrapMaven,
    [switch] $Validate,
    [switch] $Full,
    [string] $MavenVersion = "3.9.9"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$DevTools = Join-Path $Root ".dev-tools"
$MavenHome = Join-Path $DevTools "apache-maven-$MavenVersion"
$MavenCmd = Join-Path $MavenHome "bin\mvn.cmd"
$MavenRepo = if ($env:RCA_MAVEN_REPO) { $env:RCA_MAVEN_REPO } else { Join-Path $env:TEMP "rca-maven-repo" }
$TempMavenCmd = Join-Path (Join-Path $env:TEMP "rca-maven") "apache-maven-$MavenVersion\bin\mvn.cmd"
$BundledNode = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"

function Write-Step {
    param([string] $Message)
    Write-Host "[windows-dev] $Message"
}

function Get-MavenCommand {
    if ($env:MAVEN_CMD -and (Test-Path $env:MAVEN_CMD)) {
        return $env:MAVEN_CMD
    }
    if (Test-Path $MavenCmd) {
        return $MavenCmd
    }
    if (Test-Path $TempMavenCmd) {
        return $TempMavenCmd
    }
    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mvn) {
        return $mvn.Source
    }
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
        return $mvn.Source
    }
    return $null
}

function Install-Maven {
    if (Test-Path $MavenCmd) {
        return
    }

    Write-Step "Installing Maven $MavenVersion under .dev-tools"
    New-Item -ItemType Directory -Force -Path $DevTools | Out-Null
    $zip = Join-Path $DevTools "apache-maven-$MavenVersion-bin.zip"
    $url = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $DevTools -Force
}

function Require-CommandPath {
    param(
        [string] $Path,
        [string] $Name
    )
    if (-not $Path -or -not (Test-Path $Path)) {
        throw "$Name is missing"
    }
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

function Check-Tooling {
    $python = Join-Path $Root ".venv\Scripts\python.exe"
    Require-CommandPath $python "Python virtual environment"
    $pythonVersion = & $python --version
    Write-Step "Python OK: $pythonVersion"
    if ($pythonVersion -notmatch "Python 3\.(1[1-9]|[2-9][0-9])") {
        throw "Python 3.11+ is required"
    }

    $javaVersion = (cmd /c "java -version 2>&1" | Select-Object -First 1)
    Write-Step "Java OK: $javaVersion"

    $maven = Get-MavenCommand
    Require-CommandPath $maven "Maven"
    $mavenVersionLine = (cmd /c "`"$maven`" -version 2>&1" | Select-Object -First 1)
    Write-Step "Maven OK: $mavenVersionLine"

    if (Test-Path $BundledNode) {
        Write-Step "Node OK: $(& $BundledNode --version)"
    } else {
        Write-Step "Node is missing. JavaScript syntax check will be skipped."
    }
}

function Run-Validation {
    $python = Join-Path $Root ".venv\Scripts\python.exe"
    $maven = Get-MavenCommand
    $pytestTemp = Join-Path ([System.IO.Path]::GetTempPath()) ("rca-pytest-" + [System.Guid]::NewGuid().ToString("N"))

    Write-Step "Running backend tests"
    try {
        & $python -m pytest --basetemp $pytestTemp -p no:cacheprovider
        if ($LASTEXITCODE -ne 0) {
            throw "Backend tests failed"
        }
    } finally {
        if (Test-Path $pytestTemp) {
            Remove-Item -LiteralPath $pytestTemp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Step "Running Python compile check"
    & $python -m compileall -q (Join-Path $Root "backend") (Join-Path $Root "node_agent") (Join-Path $Root "tests")
    if ($LASTEXITCODE -ne 0) {
        throw "Python compile check failed"
    }

    if (Test-Path $BundledNode) {
        Write-Step "Running web console JavaScript syntax check"
        & $BundledNode --check (Join-Path $Root "web-console\src\main\resources\static\assets\console-app.js")
        if ($LASTEXITCODE -ne 0) {
            throw "JavaScript syntax check failed"
        }
    }

    Write-Step "Running Spring Boot web console tests"
    Push-Location (Join-Path $Root "web-console")
    try {
        Invoke-Maven $maven @("test")
    } finally {
        Pop-Location
    }
}

if ($Full) {
    $BootstrapMaven = $true
    $Validate = $true
}

if ($BootstrapMaven) {
    Install-Maven
}

Check-Tooling

if ($Validate) {
    Run-Validation
}
