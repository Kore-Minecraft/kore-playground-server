# Runs the compile backend in the foreground for local playground work. See README-KORE.md.
#
# Port 8090, not 8080: that is where the Kobweb dev server lives. The IR cache is kept in the working tree,
# so the first compile after a fresh checkout costs about a minute and every one after that ~6 s.
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

Write-Host "kotlin $kotlinVersion, cache $CacheDirectory, listening on http://localhost:$Port"
& java -Xmx2g -Xss16m "-Dserver.port=$Port" -jar $jar
