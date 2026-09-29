# Serves the compile backend on 8090 (8080 is Kobweb's) with the IR cache in ir-cache/, see README-KORE.md.
[CmdletBinding()]
param(
	[int] $Port = 8090,
	[string] $CacheDirectory = (Join-Path $PSScriptRoot 'ir-cache'),
	[switch] $Rebuild
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$kotlinVersion = (Select-String -Path 'gradle/libs.versions.toml' -Pattern '^kotlin\s*=\s*"(.+)"' |
	Select-Object -First 1).Matches[0].Groups[1].Value
$jar = "build/libs/kotlin-compiler-server-$kotlinVersion-SNAPSHOT.jar"

if ($Rebuild -or -not (Test-Path $jar)) {
	& ./gradlew.bat :bootJar -Pkore.slim=true
	if ($LASTEXITCODE -ne 0) { throw "bootJar failed with exit code $LASTEXITCODE" }
}

$env:KORE_JS_CACHE_DIRECTORY = $CacheDirectory
$env:KORE_JS_ANCHOR_DIRECTORY = Join-Path $PSScriptRoot 'kore-prewarm/snippets'

Write-Host "kotlin $kotlinVersion, cache $CacheDirectory, listening on http://localhost:$Port"
& java -Xmx2g -Xss16m "-Dserver.port=$Port" -jar $jar
