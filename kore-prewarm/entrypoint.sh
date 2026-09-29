#!/bin/sh
# Starts the compile backend, then warms the JS compile and the /highlight JVM type-check with train.sh's warmup/
# requests, since a cold JVM's first compiles take twice as long. KORE_WARMUP=false skips it.
# -Xmx512m peaks at ~960 MiB of container memory; -Xss matters, the compiler recurses deep.
set -eu

APP="${APP:-/kotlin-compiler-server}"
PORT="${PORT:-8080}"

AOT=""
[ -f "$APP/app.aot" ] && AOT="-XX:AOTCache=$APP/app.aot"

cd "$APP"

java \
	${AOT} \
	${JAVA_OPTS:--Xmx512m -XX:MaxMetaspaceSize=512m -Xss16m -XX:+UseSerialGC} \
	-Dserver.port="$PORT" \
	"@$APP/jvm.args" \
	com.compiler.server.CompilerApplicationKt &
pid=$!

trap 'kill -TERM "$pid" 2>/dev/null' TERM INT

if [ "${KORE_WARMUP:-true}" = "true" ] && [ -d "$APP/warmup" ]; then
	(
		until curl -sf "http://127.0.0.1:$PORT/versions" >/dev/null 2>&1; do
			kill -0 "$pid" 2>/dev/null || exit 0
			sleep 2
		done

		for request in "$APP"/warmup/*.json; do
			curl -sf -o /dev/null -X POST "http://127.0.0.1:$PORT/api/compiler/translate/js" \
				-H 'Content-Type: application/json' --data-binary "@$request" || true
			curl -sf -o /dev/null -X POST "http://127.0.0.1:$PORT/api/compiler/highlight" \
				-H 'Content-Type: application/json' --data-binary "@$request" || true
		done
		echo "warmup: JIT warmed"
	) &
fi

status=0
wait "$pid" || status=$?

# A trapped signal interrupts `wait` while the server is still shutting down.
if kill -0 "$pid" 2>/dev/null; then
	wait "$pid" || status=$?
fi

exit "$status"
