#!/bin/sh
# Freezes the classpath into an @argfile, since the AOT record, create and run steps need it byte-identical.
# A shell glob because the base image has no findutils.
set -eu

APP="${APP:-/kotlin-compiler-server}"

classpath="$APP/app.jar"
for jar in "$APP"/lib/*.jar; do
	classpath="$classpath:$jar"
done

case "$classpath" in
	*kotlin-stdlib*) ;;
	*) echo "classpath.sh: no jars found in $APP/lib" >&2; exit 1 ;;
esac

printf -- '-cp %s\n' "$classpath" > "$APP/jvm.args"

echo "classpath.sh: $(ls "$APP"/lib/*.jar | wc -l) jars frozen into $APP/jvm.args"
