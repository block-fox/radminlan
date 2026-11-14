param([string]$JavaHome = $env:JAVA_HOME, [switch]$Online)
& (Join-Path $PSScriptRoot 'build-version.ps1') -MinecraftVersion '26.3' -JavaHome $JavaHome -Online:$Online
exit $LASTEXITCODE
