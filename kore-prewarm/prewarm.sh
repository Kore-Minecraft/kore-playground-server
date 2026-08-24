#!/bin/sh
# Fills the Kotlin/JS IR build cache during the image build.
#
# A compile only pays for the Kore lowerings it is the first to reach, and the cache accumulates across
# snippets, so compiling a spread of them here turns the first visitor's "wait a minute" into "wait a few
# seconds". Runs the real server against the real endpoint, which is also what trains the JDK AOT cache.
set -eu

HOST="${PREWARM_HOST:-http://127.0.0.1:8080}"
SNIPPETS="${PREWARM_SNIPPETS:-/kore-prewarm/snippets}"
HARNESS="${PREWARM_HARNESS:-/kore-prewarm/harness.kt}"
BOOT_TIMEOUT="${PREWARM_BOOT_TIMEOUT:-300}"

waited=0
until curl -sf "$HOST/versions" >/dev/null 2>&1; do
	# A crashed server must not cost the whole boot timeout before the build says why.
	if [ -n "${PREWARM_SERVER_PID:-}" ] && ! kill -0 "$PREWARM_SERVER_PID" 2>/dev/null; then
		echo "prewarm: server exited during startup, see the stack trace above" >&2
		exit 1
	fi

	waited=$((waited + 2))
	if [ "$waited" -ge "$BOOT_TIMEOUT" ]; then
		echo "prewarm: server did not answer /versions within ${BOOT_TIMEOUT}s" >&2
		exit 1
	fi
	sleep 2
done
echo "prewarm: server up after ${waited}s"

total=0
failed=0

for snippet in "$SNIPPETS"/*.kt; do
	name=$(basename "$snippet")
	total=$((total + 1))

	jq -n --rawfile user "$snippet" --rawfile harness "$HARNESS" \
		'{args: "", files: [{name: "main.kt", text: $user}, {name: "__harness.kt", text: $harness}]}' \
		> /tmp/prewarm-request.json

	started=$(date +%s)
	if ! curl -sf -X POST "$HOST/api/compiler/translate/js" \
		-H 'Content-Type: application/json' \
		--data-binary @/tmp/prewarm-request.json \
		-o /tmp/prewarm-response.json; then
		echo "prewarm: $name - request failed" >&2
		failed=$((failed + 1))
		continue
	fi
	elapsed=$(( $(date +%s) - started ))

	errors=$(jq -r '[.errors // {} | .[][] | select(.severity == "ERROR") | .message] | join("; ")' /tmp/prewarm-response.json)
	chunks=$(jq -r '.jsFiles | length // 0' /tmp/prewarm-response.json)

	if [ -n "$errors" ]; then
		echo "prewarm: $name - did not compile in ${elapsed}s: $errors" >&2
		failed=$((failed + 1))
	else
		echo "prewarm: $name - ${elapsed}s, $chunks chunks"
	fi
done

echo "prewarm: $((total - failed))/$total snippets cached"

# A single stale snippet must not break the image, but none compiling means the klibs are wrong.
if [ "$failed" -eq "$total" ]; then
	echo "prewarm: no snippet compiled, refusing to ship an empty cache" >&2
	exit 1
fi
