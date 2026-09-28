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
| Snippets compiled in one fixed workspace, so the cache sees an edit of the same module | `KotlinToJSTranslator.kt`, `kore/KoreCompileSettings.kt` |
| An anchor module linked into every compile, so Kore's exports stop following the snippet | `KotlinToJSTranslator.kt`, `kore-prewarm/snippets/` |
| Every emitted chunk returned in dependency order as `jsFiles`, with a content hash, texts the caller already holds left out | `KotlinToJSTranslator.kt`, `ExecutionResult.kt`, `CompilerRestController.kt` |
| One compile at a time with a bounded queue, 429 past it, and a separate lane for diagnostics | `kore/CompileGate.kt`, `KotlinProjectExecutor.kt` |
| A streaming compile endpoint reporting queue position, compiler phase and output size as they happen | `CompilerRestController.kt`, `kore/KoreProgress.kt` |
| Prewarmed IR cache and JDK AOT cache built into the image, and the JIT warmed at boot | `Dockerfile`, `kore-prewarm/` |
| Slim mode, dropping the Compose/wasm playground from the build | `build.gradle.kts`, `dependencies/build.gradle.kts` |
| The JS klib folder synced rather than copied, so an old Kore klib cannot linger on the classpath | `dependencies/build.gradle.kts` |
| Upstream CI replaced by a single image build, a smoke test and three caches | `.github/workflows/kore-image.yml` |

Everything else is upstream, and upstream's own JS pipeline is untouched when the cache is off.

## What a compile costs

Measured end to end through `/api/compiler/translate/js/stream` with a client that sends the chunk hashes it
holds, like the playground page does (Kotlin 2.4.20, Kore 2.14.0-26.2, warm JVM, IR cache settled, medians of
8-20 compiles):

| Compile | 12-core desktop | same, one core | sent over the wire |
|---|---|---|---|
| switching between two prewarm snippets | 2.3 s | 3.6 s | ~10 kB |
| exact repeat of the last snippet | 0.9 s | 1.4 s | ~10 kB |
| a small edit of the last snippet | 1.8 s | 2.6 s | ~10 kB |
| a snippet using Kore APIs no prewarm snippet uses | 6.2 s | 8.0 s | ~10 kB, 15 MB once per new `Kore-kore.js` |

A fresh JVM takes about twice as long on its first few compiles, which is why the entrypoint warms it before
anyone arrives. Upstream's `-Xir-dce` default takes ~41 s every time and returns a single 3.1 MB (0.35 MB
gzipped) bundle.

The three things that make a compile this cheap, each measured on its own:

- **The IR build cache.** `-Xir-per-module -Xir-build-cache -Xcache-directory` on the second phase; it
  accumulates across snippets, which is why the image ships it trained. `-Xir-dce` is accepted and ignored
  once the cache is on, so output is ~17.7 MB of undeadcoded JavaScript and the next two points are about
  never paying for it twice.
- **A fixed snippet workspace.** The cache keys a module by its klib path and a file by its source path, and
  both used to be per-request temp folders: every compile was a brand new module, so it re-lowered every Kore
  file the snippet touched and regenerated all of `Kore-kore.js`, even for an identical repeat. Sources, klib
  and output now live in `<cache>-snippet/{src,klib,js}`, which took a repeat from 11.1 s to 2.8 s and an edit
  from 11.3 s to 5.3 s. The output folder is kept between compiles because the cache writes nothing for an
  unchanged program; it then names the entry chunk after the klib, `kotlin_playground.js`, which is renamed
  back to `playground.js`.
- **The anchor module.** In per-module output every library exports exactly what other modules use, and the
  export keys (`$_$.a`, `$_$.b`...) are reassigned when that set changes, so a snippet using another part of
  Kore than the previous one rewrote ten of the twelve chunks and re-lowered Kore files: ~11 s per switch.
  `kore-anchor` is built once from the prewarm snippets and linked into every compile as one more library,
  each snippet's `playground()` called from a `@JsExport` wrapper since the linker only loads what something
  reaches. Kore's exports then already cover whatever those snippets use, so switching between them only
  changes `playground.js`: 13.9 s became ~3 s, and 2.3 s once the client stops downloading the chunks it
  holds. The entry chunk imports the anchor's because of those exports, so `kotlin_kore_anchor.js` ships like
  any library chunk, ~50 kB that never change. A snippet reaching past the anchor still re-exports, which is
  the 6.2 s row: the wider the prewarm set, the rarer that is.

**The cache directory takes exactly one writer**, and so do the snippet workspace and the anchor folder. Two
concurrent compiles corrupt them, which is why `CompileGate` is mandatory rather than polite, and why they
belong on the container filesystem rather than a shared volume.

Set `KORE_JS_CACHE_DIRECTORY` to turn all of this on. Unset, the server answers exactly like upstream: one
`jsCode` bundle and no `jsFiles`.

## The response

`POST /api/compiler/translate/js` answers with upstream's shape plus one field:

```json
{
  "jsCode": "…",
  "jsFiles": [
    { "name": "kotlin-kotlin-stdlib.js", "hash": "4f1c…", "text": null },
    { "name": "Kore-kore.js", "hash": "9a0e…", "text": null },
    { "name": "playground.js", "hash": "c27b…", "text": "…" }
  ],
  "errors": { "main.kt": [ … ] }
}
```

`jsFiles` is absent when the cache is off. The chunks are UMD with a `globalThis` fallback and come in
dependency order, so a client evaluates them top to bottom - no bundler, no module system. `jsCode` still
carries the entry chunk in both modes; under per-module output it needs the rest loaded first.

`hash` is 128 bits of the chunk's SHA-256. Both compile endpoints take `?known=<hash>,<hash>,...`, and a chunk
whose hash is listed comes back with `"text": null`: the caller keeps texts by hash and only downloads what
changed, which with the anchor is `playground.js` alone for most compiles, ~10 kB instead of 17.7 MB.

## Progress while compiling

`POST /api/compiler/translate/js/stream` runs the very same compile as `/translate/js` and answers with
newline-delimited JSON. Every line but the last is a progress event, and the last one carries the ordinary
result under `result`:

```
{"event":"queued","ahead":2,"lane":"compile"}
{"event":"started","lane":"compile"}
{"event":"phase","name":"klib"}
{"event":"phase","name":"js","previousMs":210}
{"event":"phase","name":"collect","previousMs":1960}
{"event":"output","chunks":1,"reused":11,"bytes":7205}
{"event":"result","result":{ … }}
```

A caller reading the stream can name the wait - queued behind somebody else, type-checking, linking,
downloading - instead of showing an anonymous spinner. `output` lands before the payload and carries the
number of chunks actually sent, how many the caller already had, and the uncompressed size of what is sent,
the one number a browser cannot get from `Content-Length`: the body is gzipped and `fetch` hands the decoded
stream to the reader.

The endpoint is an async request, so `spring.mvc.async.request-timeout` is 300 s: Tomcat's 30 s default cut
every stream longer than that, a cold compile or a long queue, and the client then paid a second compile
through the plain endpoint.

NDJSON rather than server-sent events, because `text/event-stream` is not in `server.compression.mime-types`
and never would be gzipped. `application/x-ndjson` is in that list, and Tomcat flushes each line through the
gzip stream as it is written.

The pipeline reports through `KoreProgress`, a thread local sink: a compile runs synchronously on one thread
all the way down, so nothing has to be threaded through four upstream signatures. With no listener - every
other endpoint - reporting is a null check.

## Diagnostics without a compile

`POST /api/compiler/highlight` with `confType: "java"` compiles the snippet for the JVM and returns only
diagnostics, in the same 0-based `{ line, ch }` intervals as the compile endpoint. The `koreJvmDependency`
configuration puts the Kore jars in `libraries.folder.jvm`, so Kore resolves there too:

```sh
curl -s -X POST localhost:8090/api/compiler/highlight -H 'Content-Type: application/json' -d '{"confType":"java","files":[{"name":"main.kt","text":"fun playground() = ..."}]}'
```

That answers in **0.3-1.2 s**, which is what makes as-you-type squiggles possible in the editor, and lets the
page start the real compile only once the buffer type-checks. Two caveats:

- It has its **own** `CompileGate` lane, `kore.diagnostics.max-concurrent` permits with a queue four times
  that, so an editor typing at it cannot starve the compile the page actually needs.
- JVM and JS can disagree - a JVM-only API passes here and fails the real compile - so a clean highlight is
  not a promise that the compile will work.

## Configuration

| Property | Environment | Default | Meaning |
|---|---|---|---|
| `kore.js.cache-directory` | `KORE_JS_CACHE_DIRECTORY` | empty | IR build cache directory. Empty keeps the upstream pipeline. |
| `kore.js.anchor-directory` | `KORE_JS_ANCHOR_DIRECTORY` | empty | Snippets compiled into the anchor module. Empty links no anchor. |
| `kore.js.max-queued-compiles` | `KORE_JS_MAX_QUEUED_COMPILES` | 8 | Callers past this get a 429 with `Retry-After`. |
| `kore.js.queue-timeout-seconds` | `KORE_JS_QUEUE_TIMEOUT_SECONDS` | 120 | How long a caller waits for the compile slot before its own 429. |
| - | `KORE_WARMUP` | `true` | `false` skips the boot warmup compiles. |
| - | `ACCESS_CONTROL_ALLOW_ORIGIN_VALUE` | `*` | Set to `https://kore.ayfri.com`. |
| - | `JAVA_OPTS` | `-Xmx1g -XX:MaxMetaspaceSize=512m -Xss16m -XX:+UseSerialGC` | `-Xss` matters, the compiler recurses deeply. |
| - | `PORT` | 8080 | |

The image sets both directories. The snippet workspace and the anchor build live next to the cache, in
`<cache>-snippet/` and `<cache>-anchor/`.

## The anchor snippets

`kore-prewarm/snippets/` is both the prewarm set and the anchor: `site-*.kt` are the playground page's own
examples, copied from `website/playground-examples` in the Kore repo, and the numbered ones cover more of
Kore. Every file follows the playground contract, a top-level `fun playground()` and no package line.

The anchor is built by the first compile after boot. A snippet that stops compiling after a Kore bump is
left out with a warning in the log instead of failing every compile; the anchor then just covers less. Adding
snippets that use more of Kore makes the 6.2 s row rarer, at the price of a larger anchor build at boot.

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
load-bearing: a failed AOT step still ships a working image, just a few seconds slower per cold start. It also
leaves three requests in `warmup/`, which the entrypoint compiles in the background at every boot so the JIT
is warm and the anchor built before a visitor's compile.

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
can pull them. It asserts that both caches shipped and that the JVM actually started with `-XX:AOTCache`,
waits for the boot warmup, then times `kore-prewarm/smoke/unseen.kt` - deliberately not one of the prewarm
snippets - against the ~104 s an empty cache costs.

## Running it locally without Docker

```sh
./run-local.sh            # or, on Windows:
./run-local.ps1           # -Port, -CacheDirectory and -Rebuild override the defaults
```

Builds the boot jar if it is missing, then serves on 8090 - not 8080, which is where the Kobweb dev server
lives - with the IR cache in `ir-cache/` and the anchor from `kore-prewarm/snippets/`. The first compile
against an empty cache costs about a minute, so it is worth POSTing `kore-prewarm/snippets/*.kt` once before
using the page.

Point the site at it with `kore.playgroundApiUrl=http://localhost:8090` in `~/.gradle/gradle.properties`.

Timings taken on Windows run ~1 s slower than on Linux: Defender scans every freshly written chunk when
the server reads it back.

## Deploying

One replica, no scale-to-zero. See `docker-compose.kore.yml` for the shape Dokploy expects. Health check
`GET /versions` with a start period of at least 120 s - Spring Boot is slow to boot on a small machine.

**One core.** A compile is single-threaded, so a second core buys concurrency rather than speed, and capping
the container is what keeps the rest of the box responsive. The one-core column above is one core of the
desktop; a VPS vCPU is slower than that core, so the same compiles take proportionally longer there.

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
the cache without `-Xir-per-module`: a single 16.4 MB file and a full link on every compile.
