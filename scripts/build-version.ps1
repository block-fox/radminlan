param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('26.3', '26.2', '1.21.11')]
    [string]$MinecraftVersion,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$profile = Join-Path $root "versions/$MinecraftVersion/gradle.properties"
$profileLines = @(Get-Content -LiteralPath $profile)
$versionLine = $profileLines | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
if (-not $versionLine) { throw "mod_version is missing from $profile" }
$modVersion = ($versionLine -split '=', 2)[1].Trim()
$runningClients = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
    $_.CommandLine -and $_.CommandLine.Contains($root) -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main')
})
if ($runningClients.Count -gt 0) {
    throw 'Close the development client before building another version.'
}

$projectDir = if ($MinecraftVersion -eq '1.21.11') { Join-Path $root 'versions/1.21.11' } else { $root }
$gradleArguments = @('-p', $projectDir, 'build', '--no-daemon', '--console=plain')
if (-not $Online) { $gradleArguments += '--offline' }
if ($MinecraftVersion -ne '1.21.11') {
    $gradleArguments += @($profileLines | Where-Object { $_ -match '^[a-zA-Z_][a-zA-Z0-9_]*=' } |
        ForEach-Object { '-P' + $_.Trim() })
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$buildExitCode = 1
try {
    if ($JavaHome) {
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
            throw "Java executable not found under $JavaHome"
        }
        $env:JAVA_HOME = $JavaHome
        $env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
    }
    & (Join-Path $root 'gradlew.bat') @gradleArguments
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -ne 0) { throw "Gradle build failed with exit code $buildExitCode" }

    $output = Join-Path $root "versions/$MinecraftVersion/build"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    foreach ($classifier in @('', '-sources')) {
        $sourceJar = Join-Path $projectDir "build/libs/radminlan-$modVersion$classifier.jar"
        $destination = Join-Path $output "radminlan-$modVersion-$MinecraftVersion$classifier.jar"
        Copy-Item -LiteralPath $sourceJar -Destination $destination -Force
        Write-Output $destination
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}
exit $buildExitCode
