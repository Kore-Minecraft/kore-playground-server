#!/bin/sh
# Freezes the classpath into an @argfile.
#
# The AOT record, create and run steps must all see the byte-identical classpath, so it is resolved once
# here rather than left to a `lib/*` wildcard whose expansion order is the filesystem's business.
#
# A shell glob rather than find: the runtime base image ships no findutils, and an empty classpath would
# only surface much later as a NoClassDefFoundError inside the training run.
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
