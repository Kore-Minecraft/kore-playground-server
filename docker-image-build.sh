#!/bin/sh
# Builds the Kore playground image locally. CI normally does this, see .github/workflows/kore-image.yml.
#
# Expect a long build: Gradle wants several GB of RAM, and the training run compiles every prewarm snippet
# to fill the IR and AOT caches.

kotlinVersion=$(awk '{ if ($1 == "kotlin") { gsub(/"/, "", $2); print $2; } }' FS=' = ' ./gradle/libs.versions.toml)

echo "Kotlin Version for the docker: $kotlinVersion"

docker build . \
	--file Dockerfile \
	--tag kore-playground-server:local \
	--build-arg KOTLIN_VERSION=$kotlinVersion \
	--build-arg DEVELOCITY_ACCESS_KEY=$DEVELOCITY_ACCESS_KEY
