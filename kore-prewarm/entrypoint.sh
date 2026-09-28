#!/bin/sh
# Starts the compile backend, then warms its JIT with a few compiles.
#
# Memory is deliberately small: a cached JS compile is single-threaded and fits in 1 GB, and a serial GC
# beats a parallel one when the container only ever holds one core. -Xss matters, the compiler recurses deep.
#
# The IR cache ships trained, but a fresh JVM still runs the compiler cold: its first compile takes about twice
# as long as the third. Compiling the requests train.sh left in warmup/ pays that before a visitor does, and the
# compile queue keeps a visitor arriving meanwhile in line behind them. KORE_WARMUP=false skips it.
set -eu

APP="${APP:-/kotlin-compiler-server}"
PORT="${PORT:-8080}"

AOT=""
[ -f "$APP/app.aot" ] && AOT="-XX:AOTCache=$APP/app.aot"

cd "$APP"

java \
	${AOT} \
	${JAVA_OPTS:--Xmx1g -XX:MaxMetaspaceSize=512m -Xss16m -XX:+UseSerialGC} \
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
