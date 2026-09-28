# Kore playground compile backend

A downstream fork of [JetBrains/kotlin-compiler-server](https://github.com/JetBrains/kotlin-compiler-server)
that compiles Kore snippets to JavaScript for [kore.ayfri.com/playground](https://kore.ayfri.com/playground).

The server only ever **compiles**. User code runs in the visitor's own browser, so there is no sandbox to
maintain and no runaway process to kill: the only server-side cost is bounded compile work.

## What this fork changes

| Change | Where |
|---|---|
| Kore, oop and helpers JS klibs on the compile classpath (transitives come along) | `dependencies/build.gradle.kts` |
| The same three as JVM jars, so `/api/compiler/highlight` resolves Kore | `dependencies/build.gradle.kts` |
| `mavenLocal()` first, so a locally published Kore build wins over the Central release | `build-settings-logic/.../kotlin-compiler-server-version-catalog.settings.gradle.kts` |
| Kotlin/JS IR build cache and per-module output, behind one property | `kore/KoreCompileSettings.kt` |
| Every emitted chunk returned in dependency order as `jsFiles` | `KotlinToJSTranslator.kt`, `ExecutionResult.kt` |
| One compile at a time with a bounded queue, 429 past it, and a separate lane for diagnostics | `kore/CompileGate.kt`, `KotlinProjectExecutor.kt` |
| A streaming compile endpoint reporting queue position, compiler phase and output size as they happen | `CompilerRestController.kt`, `kore/KoreProgress.kt` |
| Prewarmed IR cache and JDK AOT cache built into the image | `Dockerfile`, `kore-prewarm/` |
| Slim mode, dropping the Compose/wasm playground from the build | `build.gradle.kts`, `dependencies/build.gradle.kts` |
| The JS klib folder synced rather than copied, so an old Kore klib cannot linger on the classpath | `dependencies/build.gradle.kts` |
| Upstream CI replaced by a single image build, a smoke test and three caches | `.github/workflows/kore-image.yml` |

Everything else is upstream, and upstream's own JS pipeline is untouched when the cache is off.

## The IR build cache

`-Xir-dce` and the IR build cache are mutually exclusive: with `-Xcache-directory` set, `-Xir-dce` is
accepted and then ignored. Fast compiles therefore cost payload, and that trade is the whole point:

Measured end to end through this server (`POST /api/compiler/translate/js`, Kotlin 2.4.20-RC, Kore
2.13.1-26.2, warm JVM, 12-core desktop), which is what a visitor actually waits for:

| Request | time | output |
|---|---|---|
| empty cache | 71 s | 12 chunks, 17.5 MB |
| second request, cache still settling | 27 s | 12 chunks |
| third, and the cache has settled | 16 s | 12 chunks |
| **unseen snippet against a warm cache** | **12 s** | 12 chunks |
| **repeat of a snippet the cache has seen** | **10 s** | 12 chunks |

For comparison, upstream's `-Xir-dce` default takes ~41 s every time and returns a single 3.1 MB
(0.35 MB gzipped) bundle.

The cache **accumulates across snippets**: a compile only pays for the Kore lowerings it is the first to
reach, which is why prewarming it at image build time is what makes the ordinary case the fast one. Ten of the twelve chunks are byte-identical between two unrelated snippets, so a browser caches them
once and only `Kore-kore.js` plus `playground.js` (~1.25 MB gz) move per genuinely new compile.

Set `KORE_JS_CACHE_DIRECTORY` to turn it on. Unset, the server answers exactly like upstream: one `jsCode`
bundle and no `jsFiles`.

**The cache directory takes exactly one writer.** Two concurrent compiles corrupt it, which is why
`CompileGate` is mandatory rather than polite, and why the directory belongs on the container filesystem
rather than a shared volume.

## The response

`POST /api/compiler/translate/js` answers with upstream's shape plus one field:

```json
{
  "jsCode": "…",
  "jsFiles": [
    { "name": "kotlin-kotlin-stdlib.js", "text": "…" },
    { "name": "Kore-kore.js", "text": "…" },
    { "name": "playground.js", "text": "…" }
  ],
  "errors": { "main.kt": [ … ] }
}
```

`jsFiles` is absent when the cache is off. The chunks are UMD with a `globalThis` fallback and come in
dependency order, so a client evaluates them top to bottom - no bundler, no module system. `jsCode` still
carries the entry chunk in both modes; under per-module output it needs the rest loaded first.

## Progress while compiling

`POST /api/compiler/translate/js/stream` runs the very same compile as `/translate/js` and answers with
newline-delimited JSON. Every line but the last is a progress event, and the last one carries the ordinary
result under `result`:

```
{"event":"queued","ahead":2,"lane":"compile"}
{"event":"started","lane":"compile"}
{"event":"phase","name":"klib"}
{"event":"phase","name":"js","previousMs":3103}
{"event":"phase","name":"collect","previousMs":15301}
{"event":"output","chunks":12,"bytes":17047408}
{"event":"result","result":{ … }}
```

A caller reading the stream can name the wait - queued behind somebody else, type-checking, linking,
downloading 16 MB - instead of showing an anonymous spinner for 6-35 s. `output` lands before the payload
and carries its uncompressed size, which is the one number a browser cannot get from `Content-Length`:
the body is gzipped and `fetch` hands the decoded stream to the reader.

NDJSON rather than server-sent events, because `text/event-stream` is not in `server.compression.mime-types`
and never would be gzipped: the result alone is ~16 MB raw against 1.7 MB gzipped. `application/x-ndjson` is
in that list, and Tomcat flushes each line through the gzip stream as it is written - measured, the phase
events arrive seconds before the result.

The pipeline reports through `KoreProgress`, a thread local sink: a compile runs synchronously on the
request thread all the way down, so nothing has to be threaded through four upstream signatures. With no
listener - every other endpoint - reporting is a null check.

## Diagnostics without a compile

`POST /api/compiler/highlight` with `confType: "java"` compiles the snippet for the JVM and returns only
diagnostics, in the same 0-based `{ line, ch }` intervals as the compile endpoint. The `koreJvmDependency`
configuration puts the Kore jars in `libraries.folder.jvm`, so Kore resolves there too:

```sh
curl -s -X POST localhost:8090/api/compiler/highlight -H 'Content-Type: application/json' -d '{"confType":"java","files":[{"name":"main.kt","text":"fun playground() = ..."}]}'
```

That answers in **0.3-1.2 s** against 6-20 s for the JS path, which is what makes as-you-type squiggles
possible in the editor. Two caveats:

- It has its **own** `CompileGate` lane, `kore.diagnostics.max-concurrent` permits with a queue four times
  that, so an editor typing at it cannot starve the compile the page actually needs.
- JVM and JS can disagree - a JVM-only API passes here and fails the real compile - so a clean highlight is
  not a promise that Run will work.

## Configuration

| Property | Environment | Default | Meaning |
|---|---|---|---|
| `kore.js.cache-directory` | `KORE_JS_CACHE_DIRECTORY` | empty | IR build cache directory. Empty keeps the upstream pipeline. |
| `kore.js.max-queued-compiles` | `KORE_JS_MAX_QUEUED_COMPILES` | 8 | Callers past this get a 429 with `Retry-After`. |
| `kore.js.queue-timeout-seconds` | `KORE_JS_QUEUE_TIMEOUT_SECONDS` | 120 | How long a caller waits for the compile slot before its own 429. |
| - | `ACCESS_CONTROL_ALLOW_ORIGIN_VALUE` | `*` | Set to `https://kore.ayfri.com`. |
| - | `JAVA_OPTS` | `-Xmx1g -XX:MaxMetaspaceSize=512m -Xss16m -XX:+UseSerialGC` | `-Xss` matters, the compiler recurses deeply. |
| - | `PORT` | 8080 | |

## Building the image

CI does it (`.github/workflows/kore-image.yml`) and pushes to
`ghcr.io/kore-minecraft/kore-playground-server`. The build needs several GB of RAM and compiles every
prewarm snippet, so it does not belong on the deployment box.

Locally:

```sh
./docker-image-build.sh
```

Two phases. **Gradle runs on the host**, not in a Docker layer, because a layer cannot keep the dependency
and build caches between runs and re-resolving them is most of a cold build; the image then consumes what
the host produced:

```
build/libs/kotlin-compiler-server-<kotlin>-SNAPSHOT.jar
<kotlin>/        JVM klibs
<kotlin>-js/     JS klibs, Kore included
ir-cache-seed/   a trained IR cache, or empty
```

**Docker assembles and trains.** The training run starts the real server and POSTs every snippet in
`kore-prewarm/snippets/` at the real endpoint, which fills both caches at once - the IR cache from the
compiles, the JDK AOT cache from `-XX:AOTMode=record` followed by `-XX:AOTMode=create`. Neither is
load-bearing: a failed AOT step still ships a working image, just a few seconds slower per cold start.

A snippet that stops compiling after a Kore version bump prints `prewarm: <name> - did not compile` and is
skipped. The build only fails when *none* of them compile, which means the klibs are wrong.

### Slim mode

`-Pkore.slim=true` drops the Compose/wasm playground, which this backend never serves. That removes the
skiko, binaryen, node and npm-install chain along with `:cache-maker` entirely - 29 tasks instead of 123 -
and stops the wasm and compose-wasm klibs (33 MB) from being downloaded and copied. `application.properties`
gets a constant where the compose-wasm runtime hash would be.

Unset, the build is upstream's.

### What CI caches

| Cache | Carried by | Buys |
|---|---|---|
| Gradle user home and build cache | `gradle/actions/setup-gradle` | dependency resolution and compilation on an unchanged tree |
| The trained Kotlin/JS IR cache | `actions/cache` on `ir-cache-seed`, refilled from the `ir-cache-export` stage of the image | a training run that pays only for lowerings nobody has reached yet |
| Docker layers | `type=gha` | the assemble stage, and the whole prewarm stage when its inputs are unchanged |

The IR cache key is the Kotlin version, the Kore version and a hash of `kore-prewarm/` plus the fork's own
compile settings, so a version bump starts a fresh lineage rather than seeding a stale cache.

The image is `load`ed rather than pushed first, so the smoke test runs against the exact bytes before anyone
can pull them. It asserts that both caches shipped, that the JVM actually started with `-XX:AOTCache`, and
that `kore-prewarm/smoke/unseen.kt` - deliberately not one of the prewarm snippets - compiles in well under
the ~104 s an empty cache costs.

## Running it locally without Docker

```sh
./run-local.sh            # or, on Windows:
./run-local.ps1           # -Port, -CacheDirectory and -Rebuild override the defaults
```

Builds the boot jar if it is missing, then serves on 8090 - not 8080, which is where the Kobweb dev server
lives - with the IR cache in `ir-cache/`. The first compile against an empty cache costs about a minute and
every one after that ~6 s, so it is worth POSTing `kore-prewarm/snippets/*.kt` once before using the page.

Point the site at it with `kore.playgroundApiUrl=http://localhost:8090` in `~/.gradle/gradle.properties`.

## Deploying

One replica, no scale-to-zero, **one core**. A Kotlin/JS compile is single-threaded, so a second core buys
concurrency rather than speed, and capping the container is what keeps the rest of the box responsive.

See `docker-compose.kore.yml` for the shape Dokploy expects. Health check `GET /versions` with a start
period of at least 120 s - Spring Boot is slow to boot on a small machine.

## Keeping up with upstream

```sh
git remote add upstream https://github.com/JetBrains/kotlin-compiler-server.git
git fetch upstream
git merge upstream/master
```

Two version bumps carry the fork forward: the upstream tag, and the Kore coordinate in
`dependencies/build.gradle.kts`. The Kotlin/JS klib format is stable across compiler versions - Kore built
with 2.4.0 links fine against a 2.4.20 server - so the two versions do not have to match.

The patch surface upstream can break is small and lives in `KotlinToJSTranslator.doTranslateWithIr` plus the
`CompilationResult<JsTranslationOutput>` type it returns. If that ever gets painful, the no-fork fallback is
the cache without `-Xir-per-module`: a single 16.4 MB file and ~20 s per warm compile instead of ~6 s.
