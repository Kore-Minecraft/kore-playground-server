#!/bin/sh
# Serves the compile backend on 8090 (8080 is Kobweb's) with the IR cache in ir-cache/, see README-KORE.md.
set -eu

kotlinVersion=$(awk '{ if ($1 == "kotlin") { gsub(/"/, "", $2); print $2; } }' FS=' = ' ./gradle/libs.versions.toml)
jar="build/libs/kotlin-compiler-server-${kotlinVersion}-SNAPSHOT.jar"

[ -f "$jar" ] || ./gradlew :bootJar -Pkore.slim=true

# From the repository root, libraries.folder.js being a relative path.
KORE_JS_CACHE_DIRECTORY="$PWD/ir-cache" KORE_JS_ANCHOR_DIRECTORY="$PWD/kore-prewarm/snippets" exec java \
	-Xmx2g -Xss16m \
	-Dserver.port="${PORT:-8090}" \
	-jar "$jar"
