$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$properties = Join-Path $root 'gradle.properties'
$profile = Join-Path $root 'gradle.properties.26.2'
$backup = Join-Path $root 'gradle.properties.radminlan-backup'

Copy-Item -LiteralPath $properties -Destination $backup -Force
try {
    Copy-Item -LiteralPath $profile -Destination $properties -Force
    Push-Location $root
    & (Join-Path $root 'gradlew.bat') build --no-daemon
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
    Move-Item -LiteralPath $backup -Destination $properties -Force
}
