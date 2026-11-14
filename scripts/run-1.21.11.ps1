param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradleArguments = @('-p', (Join-Path $root 'versions/1.21.11'), 'runClient', '--no-daemon', '--console=plain')
if (-not $Online) { $gradleArguments += '--offline' }
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
    & (Join-Path $root 'gradlew.bat') @gradleArguments
    $clientExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}
exit $clientExitCode
