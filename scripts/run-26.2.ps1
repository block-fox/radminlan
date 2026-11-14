param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$profile = Join-Path $root 'versions/26.2/gradle.properties'
$profileArguments = @(Get-Content -LiteralPath $profile |
    Where-Object { $_ -match '^[a-zA-Z_][a-zA-Z0-9_]*=' } |
    ForEach-Object { '-P' + $_.Trim() })
$gradleArguments = @('runClient', '--no-daemon', '--console=plain')
if (-not $Online) { $gradleArguments += '--offline' }
$gradleArguments += $profileArguments
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path

try {
    if ($JavaHome) {
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
            throw "Java executable not found under $JavaHome"
        }
        $env:JAVA_HOME = $JavaHome
        $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
    }
    Push-Location $root
    try {
        & (Join-Path $root 'gradlew.bat') @gradleArguments
        $clientExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}

exit $clientExitCode
