param(
    [switch] $BootstrapMaven
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "windows-dev-check.ps1"
$arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $script, "-Validate")
if ($BootstrapMaven) {
    $arguments += "-BootstrapMaven"
}

& powershell @arguments
exit $LASTEXITCODE
