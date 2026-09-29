# Kore playground compile backend

A fork of [JetBrains/kotlin-compiler-server](https://github.com/JetBrains/kotlin-compiler-server) compiling Kore
snippets to JavaScript for [kore.ayfri.com/playground](https://kore.ayfri.com/playground). It only compiles: the
snippet runs in the visitor's browser, so there is nothing to sandbox.

## What the fork changes

| Change | Where |
|---|---|
| Kore, oop and helpers as JS klibs for the compile and JVM jars for `/highlight`, synced so a bump drops the old ones | `dependencies/build.gradle.kts` |
| `mavenLocal()` first, so a locally published Kore wins | `build-settings-logic/.../version-catalog.settings.gradle.kts` |
| Kotlin/JS IR build cache with per-module output, snippets compiled in a fixed workspace | `kore/KoreCompileSettings.kt`, `KotlinToJSTranslator.kt` |
| An anchor module built from `kore-prewarm/snippets/`, linked into every compile so Kore's chunks stay unchanged between snippets | `KotlinToJSTranslator.kt` |
| Every chunk returned in evaluation order with a hash, texts the caller holds left out | `KotlinToJSTranslator.kt`, `ExecutionResult.kt` |
| One compile at a time with a bounded queue, a separate lane for `/highlight` | `kore/CompileGate.kt`, `KotlinProjectExecutor.kt` |
| A streaming compile endpoint | `CompilerRestController.kt`, `kore/KoreProgress.kt` |
| Slim mode (`kore.slim`, on in `gradle.properties` with the build cache), dropping the Compose/wasm playground from the build | `build.gradle.kts`, `dependencies/build.gradle.kts` |
| An image with a trained IR cache and JDK AOT cache, warmed at boot | `Dockerfile`, `kore-prewarm/` |
| One CI workflow building, smoke testing and deploying the image | `.github/workflows/kore-image.yml` |

With `KORE_JS_CACHE_DIRECTORY` unset the JS pipeline is upstream's.

## Endpoints

- `POST /api/compiler/translate/js?known=<hash>,…` answers upstream's shape plus `jsFiles: [{ name, hash, text }]`, UMD
  chunks in evaluation order, `text` being `null` for a hash listed in `known`. `jsCode` is the entry chunk.
- `POST /api/compiler/translate/js/stream?known=…` runs the same compile as NDJSON lines: `queued` (`ahead`, `lane`),
  `started`, `phase` (`name`, `previousMs`), `output` (`chunks`, `reused`, decoded `bytes`), then `result`, `busy`
  (`message`, `retryAfterSeconds`) or `error` (`message`). The status is always 200.
- `POST /api/compiler/highlight` with `confType: "java"` type-checks on the JVM in ~1 s. JVM and JS can disagree, so the
  compile's diagnostics win.

A full queue answers 429 with `Retry-After` on the plain endpoints and `busy` on the stream.

## Cost

Kotlin 2.4.20, Kore 2.15.0-26.2, warm JVM, client sending the hashes it holds, medians of 8-20 compiles:

| Compile | 12-core desktop | one core |
|---|---|---|
| switching between two prewarm snippets | 2.3 s | 3.6 s |
| exact repeat | 1.0 s | 1.5 s |
| small edit | 1.9 s | 2.9 s |
| Kore APIs no prewarm snippet uses | 5.7 s | 8.2 s |

A browser's first compile downloads 17.5 MB (1.8 MB gzipped), later ones ~10 kB. With `-Xmx512m` the container peaks
at ~960 MiB and idles at ~940 MiB; `-Xmx1g` peaks at 1408 MiB for no speed gain. The IR cache, the snippet workspace
and the anchor take one writer each, hence the single-flight queue and no shared volume.

## Configuration

| Property | Environment | Default |
|---|---|---|
| `kore.js.cache-directory` | `KORE_JS_CACHE_DIRECTORY` | empty, set in the image |
| `kore.js.anchor-directory` | `KORE_JS_ANCHOR_DIRECTORY` | empty, set in the image |
| `kore.js.max-queued-compiles` | `KORE_JS_MAX_QUEUED_COMPILES` | 8, the running compile included |
| `kore.js.queue-timeout-seconds` | `KORE_JS_QUEUE_TIMEOUT_SECONDS` | 120 |
| `kore.diagnostics.max-concurrent` | - | 2, with a queue of 8 |
| - | `ACCESS_CONTROL_ALLOW_ORIGIN_VALUE` | `*` |
| - | `JAVA_OPTS` | `-Xmx512m -XX:MaxMetaspaceSize=512m -Xss16m -XX:+UseSerialGC` |
| - | `KORE_WARMUP` | `true`, compiles and type-checks three snippets at boot |

## Building and running

- **CI** builds on every push to `master`, smoke tests at `--cpus 1.0 --memory 1200m`, pushes
  `ghcr.io/kore-minecraft/kore-playground-server:{latest,kore-<version>,<sha>}`, then calls the `DOKPLOY_DEPLOY_HOOK`
  secret when set, Dokploy's webhook URL from the application's Deployments tab.
- **Locally**, `./docker-image-build.sh` builds `kore-playground-server:local`.
- **Without Docker**, `./run-local.ps1` (or `.sh`) serves on 8090, then point the site at it with
  `kore.playgroundApiUrl=http://localhost:8090` in `~/.gradle/gradle.properties`.

`kore-prewarm/snippets/` is the prewarm set and the anchor: `site-*.kt` copy the site's examples from
`website/playground-examples` in the Kore repository, keep them in sync. A snippet that stops compiling after a Kore
bump is left out of the anchor with a warning.

## Deploying

`docker-compose.kore.yml` is the deployed shape: one replica, `cpus: 1.0`, 1200M, health check on `/versions` with a
120 s start period, no scale-to-zero. Dokploy takes memory in bytes (`1258291200`) and CPUs in nano-CPUs
(`1000000000`).

## Keeping up with upstream

`git fetch upstream && git merge upstream/master`, then bump `kore` in `gradle/libs.versions.toml`. Kotlin/JS
klibs are stable across compiler versions, so Kore and the server's Kotlin do not have to match. The upstream patch
surface is `KotlinToJSTranslator.doTranslateWithIr`, the controller and `KotlinProjectExecutor`.
