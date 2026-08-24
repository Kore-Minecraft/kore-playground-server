#!/bin/sh
# Builds the Kore playground image locally. CI normally does this, see .github/workflows/kore-image.yml.
#
# Two phases: Gradle produces the boot jar and the klib folders on the host, then Docker assembles them and
# trains the IR and AOT caches. Expect the training run to take a few minutes on an empty ir-cache-seed.
set -eu

kotlinVersion=$(awk '{ if ($1 == "kotlin") { gsub(/"/, "", $2); print $2; } }' FS=' = ' ./gradle/libs.versions.toml)

echo "Kotlin version for the docker: $kotlinVersion"

# Slim mode drops the Compose/wasm playground, which this backend never serves. See README-KORE.md.
./gradlew :bootJar -Pkore.slim=true --build-cache

mkdir -p ir-cache-seed

docker build . \
	--file Dockerfile \
	--tag kore-playground-server:local \
	--build-arg KOTLIN_VERSION="$kotlinVersion"
