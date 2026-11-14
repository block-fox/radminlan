param([string]$JavaHome = $env:JAVA_HOME, [switch]$Online)
& (Join-Path $PSScriptRoot 'build-version.ps1') -MinecraftVersion '1.21.11' -JavaHome $JavaHome -Online:$Online
exit $LASTEXITCODE
