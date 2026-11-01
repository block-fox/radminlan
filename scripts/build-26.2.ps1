$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$properties = Join-Path $root 'gradle.properties'
$profile = Join-Path $root 'versions/26.2/gradle.properties'
$backup = Join-Path $root 'gradle.properties.radminlan-backup'
$output = Join-Path $root 'versions/26.2/build'
$versionLine = Get-Content -LiteralPath $profile | Where-Object { $_ -match '^\s*mod_version\s*=' } | Select-Object -First 1
if (-not $versionLine) { throw "mod_version is missing from $profile" }
$modVersion = ($versionLine -split '=', 2)[1].Trim()
$buildExitCode = 0
$locationPushed = $false

if (Test-Path -LiteralPath $backup) {
    throw "A previous build backup already exists: $backup"
}

Copy-Item -LiteralPath $properties -Destination $backup -Force
try {
    Copy-Item -LiteralPath $profile -Destination $properties -Force
    Push-Location $root
    $locationPushed = $true
    & (Join-Path $root 'gradlew.bat') clean build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

    New-Item -ItemType Directory -Force -Path $output | Out-Null
    Copy-Item -LiteralPath (Join-Path $root "build/libs/radminlan-$modVersion.jar") `
        -Destination (Join-Path $output "radminlan-$modVersion-26.2.jar") -Force
    Copy-Item -LiteralPath (Join-Path $root "build/libs/radminlan-$modVersion-sources.jar") `
        -Destination (Join-Path $output "radminlan-$modVersion-26.2-sources.jar") -Force
} catch {
    $buildExitCode = 1
    throw
} finally {
    if ($locationPushed) { Pop-Location }
    if (Test-Path -LiteralPath $backup) {
        Move-Item -LiteralPath $backup -Destination $properties -Force
    }
}

exit $buildExitCode
