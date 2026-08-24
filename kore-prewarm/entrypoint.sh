#!/bin/sh
# Starts the compile backend.
#
# Memory is deliberately small: a cached JS compile is single-threaded and fits in 1 GB, and a serial GC
# beats a parallel one when the container only ever holds one core. -Xss matters, the compiler recurses deep.
set -eu

APP="${APP:-/kotlin-compiler-server}"

AOT=""
[ -f "$APP/app.aot" ] && AOT="-XX:AOTCache=$APP/app.aot"

cd "$APP"

exec java \
	${AOT} \
	${JAVA_OPTS:--Xmx1g -XX:MaxMetaspaceSize=512m -Xss16m -XX:+UseSerialGC} \
	-Dserver.port="${PORT:-8080}" \
	"@$APP/jvm.args" \
	com.compiler.server.CompilerApplicationKt
