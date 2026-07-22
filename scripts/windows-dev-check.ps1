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
    $mavenVersionOutput = @(cmd /c "`"$maven`" -version 2>&1")
    $mavenVersionLine = $mavenVersionOutput | Select-Object -First 1
    Write-Step "Maven OK: $mavenVersionLine"
    $mavenJavaVersionLine = $mavenVersionOutput | Where-Object { $_ -match '^Java version:' } | Select-Object -First 1
    if (-not $mavenJavaVersionLine -or $mavenJavaVersionLine -notmatch '^Java version:\s+(?<major>\d+)') {
        throw "Unable to determine the Java runtime used by Maven"
    }
    if ([int] $Matches.major -lt 21) {
        throw "Maven must use Java 21+. Detected: $mavenJavaVersionLine. Set JAVA_HOME to a JDK 21 installation."
    }
    Write-Step "Maven runtime OK: $mavenJavaVersionLine"

    if (Test-Path $BundledNode) {
        Write-Step "Node OK: $(& $BundledNode --version)"
    } else {
        Write-Step "System Node is missing. The Frontend Maven profile will install its pinned Node.js runtime."
    }
}

function Run-Validation {
    $python = Join-Path $Root ".venv\Scripts\python.exe"
    $maven = Get-MavenCommand
    $pytestTemp = Join-Path ([System.IO.Path]::GetTempPath()) ("rca-pytest-" + [System.Guid]::NewGuid().ToString("N"))

    Write-Step "Running node agent tests"
    try {
        & $python -m pytest --basetemp $pytestTemp -p no:cacheprovider
        if ($LASTEXITCODE -ne 0) {
            throw "Node Agent tests failed"
        }
    } finally {
        if (Test-Path $pytestTemp) {
            Remove-Item -LiteralPath $pytestTemp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Step "Running Python compile check"
    & $python -m compileall -q (Join-Path $Root "node_agent") (Join-Path $Root "tests")
    if ($LASTEXITCODE -ne 0) {
        throw "Python compile check failed"
    }

    Write-Step "Running integrated Spring Boot and Frontend build"
    Push-Location (Join-Path $Root "web-console")
    try {
        Invoke-Maven $maven @("-Pfrontend", "verify")
    } finally {
        Pop-Location
    }

    $frontendNpm = Join-Path $Root "web-console\frontend\node\npm.cmd"
    Require-CommandPath $frontendNpm "Maven-managed Frontend npm"
    Write-Step "Running Frontend unit tests"
    $originalPath = $env:PATH
    $env:PATH = "$(Split-Path -Parent $frontendNpm);$env:PATH"
    Push-Location (Join-Path $Root "web-console\frontend")
    try {
        & $frontendNpm test
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend tests failed"
        }
    } finally {
        Pop-Location
        $env:PATH = $originalPath
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
