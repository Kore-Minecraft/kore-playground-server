# Kore playground compile backend

A downstream fork of [JetBrains/kotlin-compiler-server](https://github.com/JetBrains/kotlin-compiler-server)
that compiles Kore snippets to JavaScript for [kore.ayfri.com/playground](https://kore.ayfri.com/playground).

The server only ever **compiles**. User code runs in the visitor's own browser, so there is no sandbox to
maintain and no runaway process to kill: the only server-side cost is bounded compile work.

## What this fork changes

| Change | Where |
|---|---|
| Kore, oop and helpers JS klibs on the compile classpath (transitives come along) | `dependencies/build.gradle.kts` |
| `mavenLocal()` first, so a locally published Kore build wins over the Central release | `build-settings-logic/.../kotlin-compiler-server-version-catalog.settings.gradle.kts` |
| Kotlin/JS IR build cache and per-module output, behind one property | `kore/KoreCompileSettings.kt` |
| Every emitted chunk returned in dependency order as `jsFiles` | `KotlinToJSTranslator.kt`, `ExecutionResult.kt` |
| One compile at a time with a bounded queue, 429 past it | `kore/CompileGate.kt`, `KotlinProjectExecutor.kt` |
| Prewarmed IR cache and JDK AOT cache built into the image | `Dockerfile`, `kore-prewarm/` |
| Upstream CI replaced by a single image build | `.github/workflows/kore-image.yml` |

Everything else is upstream, and upstream's own JS pipeline is untouched when the cache is off.

## The IR build cache

`-Xir-dce` and the IR build cache are mutually exclusive: with `-Xcache-directory` set, `-Xir-dce` is
accepted and then ignored. Fast compiles therefore cost payload, and that trade is the whole point:

Measured end to end through this server (`POST /api/compiler/translate/js`, Kotlin 2.4.20-RC, Kore
2.8.0-26.1.2, warm JVM), which is what a visitor actually waits for:

| Request | time | output |
|---|---|---|
| empty cache | 104 s | 12 chunks, 16.9 MB |
| second request, cache still settling | 49 s | 12 chunks |
| **repeat of a snippet the cache has seen** | **7.1 s** | 12 chunks |
| **unseen snippet against a warm cache** | **14.6 s** | 12 chunks |
| that snippet repeated | 8.4 s | 12 chunks |

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

The build has three stages: Gradle build, a **training run** that starts the real server and POSTs every
snippet in `kore-prewarm/snippets/` at it, and the final image. That one run fills both caches - the IR
cache from the compiles, the JDK AOT cache from `-XX:AOTMode=record`. Neither is load-bearing: a failed AOT
step still ships a working image, just a few seconds slower per cold start.

A snippet that stops compiling after a Kore version bump prints `prewarm: <name> - did not compile` and is
skipped. The build only fails when *none* of them compile, which means the klibs are wrong.

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
