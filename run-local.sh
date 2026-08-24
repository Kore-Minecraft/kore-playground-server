#!/bin/sh
# Runs the compile backend in the foreground for local playground work. See README-KORE.md.
#
# Port 8090, not 8080: that is where the Kobweb dev server lives. The IR cache is kept in the working tree,
# so the first compile after a fresh checkout costs about a minute and every one after that ~10 s.
set -eu

kotlinVersion=$(awk '{ if ($1 == "kotlin") { gsub(/"/, "", $2); print $2; } }' FS=' = ' ./gradle/libs.versions.toml)
jar="build/libs/kotlin-compiler-server-${kotlinVersion}-SNAPSHOT.jar"

[ -f "$jar" ] || ./gradlew :bootJar -Pkore.slim=true

# From the repository root: libraries.folder.js is a relative path.
KORE_JS_CACHE_DIRECTORY="$PWD/ir-cache" exec java \
	-Xmx2g -Xss16m \
	-Dserver.port="${PORT:-8090}" \
	-jar "$jar"
