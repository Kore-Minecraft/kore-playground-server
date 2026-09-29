#!/bin/sh
# Builds the image locally as kore-playground-server:local: Gradle on the host, then Docker trains the caches.
set -eu

kotlinVersion=$(awk '{ if ($1 == "kotlin") { gsub(/"/, "", $2); print $2; } }' FS=' = ' ./gradle/libs.versions.toml)

echo "Kotlin version for the docker: $kotlinVersion"

./gradlew :bootJar -Pkore.slim=true --build-cache

mkdir -p ir-cache-seed

docker build . \
	--file Dockerfile \
	--tag kore-playground-server:local \
	--build-arg KOTLIN_VERSION="$kotlinVersion"
