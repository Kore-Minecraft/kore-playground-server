#!/bin/sh
# Builds both caches in one pass during the image build.
#
# The IR build cache and the JDK AOT cache want the same thing - the server actually doing its job - so a
# single training run serves both: start under -XX:AOTMode=record, compile every prewarm snippet, shut down
# cleanly so the recording is flushed, then turn the recording into a cache.
#
# Neither cache is load-bearing. If the AOT step fails the image still ships, just a few seconds slower per
# cold start, and entrypoint.sh only passes -XX:AOTCache when the file is actually there.
set -eu

APP="${APP:-/kotlin-compiler-server}"
ARGS="@$APP/jvm.args"

cd "$APP"

echo "train: starting server for the training run"
java -XX:AOTMode=record -XX:AOTConfiguration="$APP/app.aotconf" \
	-Xss16m -Dserver.port=8080 $ARGS com.compiler.server.CompilerApplicationKt &
pid=$!

if PREWARM_SERVER_PID=$pid /kore-prewarm/prewarm.sh; then
	prewarmed=0
else
	prewarmed=$?
fi

echo "train: stopping server"
kill -TERM "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true

[ "$prewarmed" -eq 0 ] || exit "$prewarmed"

if [ -f "$APP/app.aotconf" ]; then
	echo "train: building the AOT cache"
	java -XX:AOTMode=create -XX:AOTConfiguration="$APP/app.aotconf" -XX:AOTCache="$APP/app.aot" \
		$ARGS || echo "train: AOT cache creation failed, continuing without it" >&2
	rm -f "$APP/app.aotconf"
else
	echo "train: no AOT recording produced, continuing without it" >&2
fi

# The training run leaves its own compiles behind; only the IR cache is worth keeping.
rm -rf "$APP/logs" /tmp/prewarm-*.json

# Three different snippets for entrypoint.sh to compile at boot: repeating one would only warm the no-op path.
mkdir -p "$APP/warmup"
for snippet in $(ls /kore-prewarm/snippets/*.kt | head -3); do
	jq -n --rawfile user "$snippet" --rawfile harness /kore-prewarm/harness.kt \
		'{args: "", files: [{name: "main.kt", text: $user}, {name: "__harness.kt", text: $harness}]}' \
		> "$APP/warmup/$(basename "$snippet" .kt).json"
done

cp /kore-prewarm/entrypoint.sh "$APP/entrypoint.sh"
chmod +x "$APP/entrypoint.sh"

du -sh "$APP/ir-cache" 2>/dev/null || true
