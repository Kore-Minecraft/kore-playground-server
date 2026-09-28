import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsCompilerAttribute
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetAttribute

plugins {
    id("base-kotlin-jvm-conventions")
}

// Kore playground: slim mode keeps only the JVM and JS klibs, the two the compile backend actually serves.
val koreSlim = providers.gradleProperty("kore.slim").map(String::toBoolean).getOrElse(false)

val kotlinDependency: Configuration by configurations.creating {
    isTransitive = false
}

val kotlinCompilerPluginDependency: Configuration by configurations.creating {
    isTransitive = false
    isCanBeResolved = true
    isCanBeConsumed = false
}

val kotlinJsDependency: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false

    attributes {
        attribute(
            KotlinPlatformType.attribute,
            KotlinPlatformType.js
        )
        attribute(
            KotlinJsCompilerAttribute.jsCompilerAttribute,
            KotlinJsCompilerAttribute.ir
        )
    }
}

val koreJvmDependency: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false

    // The JVM libraries folder already ships the stdlib and the kotlinx artifacts, at the compiler's own versions.
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")

    attributes {
        attribute(
            KotlinPlatformType.attribute,
            KotlinPlatformType.jvm
        )
    }
}

val kotlinWasmDependency: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false

    attributes {
        attribute(
            KotlinPlatformType.attribute,
            KotlinPlatformType.wasm
        )
        attribute(
            KotlinWasmTargetAttribute.wasmTargetAttribute,
            KotlinWasmTargetAttribute.js
        )
    }
}

val kotlinComposeWasmDependency: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false

    attributes {
        attribute(
            KotlinPlatformType.attribute,
            KotlinPlatformType.wasm
        )
        attribute(
            KotlinWasmTargetAttribute.wasmTargetAttribute,
            KotlinWasmTargetAttribute.js
        )
    }
}

val composeWasmCompilerPlugins: Configuration by configurations.creating {
    isTransitive = false
}

val copyDependencies by tasks.creating(Copy::class) {
    from(kotlinDependency)
    into(libJVMFolder)
}

val copyKoreJvmDependencies by tasks.creating(Copy::class) {
    from(koreJvmDependency)
    into(libJVMFolder)
}

val copyCompilerPluginDependencies by tasks.creating(Copy::class) {
    from(kotlinCompilerPluginDependency)
    into(compilerPluginsForJVMFolder)
}

// Sync, not Copy: this folder is the JS compile classpath and its contents change with the Kore version,
// so a leftover klib from an earlier version would sit next to the new one and be compiled against too.
val copyJSDependencies by tasks.creating(Sync::class) {
    from(kotlinJsDependency)
    into(libJSFolder)
}

val copyWasmDependencies by tasks.creating(Copy::class) {
    from(kotlinWasmDependency)
    into(libWasmFolder)
}

val copyComposeWasmDependencies by tasks.creating(Copy::class) {
    from(kotlinComposeWasmDependency)
    into(libComposeWasmFolder)
}

val copyComposeWasmCompilerPlugins by tasks.creating(Copy::class) {
    from(composeWasmCompilerPlugins)
    into(libComposeWasmCompilerPluginsFolder)
}

dependencies {
    kotlinDependency(libs.junit)
    kotlinDependency(libs.hamcrest)
    kotlinDependency(libs.bundles.jackson)
    // Kotlin libraries
    kotlinDependency(libs.kotlin.stdlib)
    kotlinDependency(libs.kotlin.test)
    kotlinDependency(libs.kotlin.test.junit)
    kotlinDependency(libs.kotlinx.coroutines.core.jvm)
    kotlinDependency(libs.kotlinx.coroutines.test)
    kotlinDependency(libs.kotlinx.datetime)
    kotlinDependency(libs.kotlinx.io.bytestring)
    kotlinDependency(libs.kotlinx.io.core)
    kotlinDependency(libs.kotlinx.serialization.json.jvm)
    kotlinDependency(libs.kotlinx.serialization.core.jvm)
    kotlinCompilerPluginDependency(libs.kotlin.serialization.plugin)
    kotlinJsDependency(libs.kotlin.stdlib.js)
    kotlinJsDependency(libs.kotlin.dom.api.compat)

    // Kore playground: transitive, so knbt / kotlinx-io / kotlinx-serialization JS klibs come along.
    kotlinJsDependency("io.github.ayfri.kore:kore:2.14.0-26.2")
    kotlinJsDependency("io.github.ayfri.kore:oop:2.14.0-26.2")
    kotlinJsDependency("io.github.ayfri.kore:helpers:2.14.0-26.2")

    // Kore playground: the same libraries as JVM jars, so `/api/compiler/highlight` resolves Kore in ~0.5 s.
    koreJvmDependency("io.github.ayfri.kore:kore:2.14.0-26.2")
    koreJvmDependency("io.github.ayfri.kore:oop:2.14.0-26.2")
    koreJvmDependency("io.github.ayfri.kore:helpers:2.14.0-26.2")

    kotlinWasmDependency(libs.kotlin.stdlib.wasm.js)

    // compose
    kotlinComposeWasmDependency(libs.kotlin.stdlib.wasm.js)
    kotlinComposeWasmDependency(libs.bundles.compose)
    kotlinComposeWasmDependency(libs.kotlinx.coroutines.core.compose.wasm)

    composeWasmCompilerPlugins(libs.kotlin.compose.compiler.plugin)
}

project.tasks.jar.configure {
    dependsOn(copyDependencies)
    dependsOn(copyKoreJvmDependencies)
    dependsOn(copyCompilerPluginDependencies)
    dependsOn(copyJSDependencies)

    if (!koreSlim) {
        dependsOn(copyWasmDependencies)
        dependsOn(copyComposeWasmDependencies)
        dependsOn(copyComposeWasmCompilerPlugins)
    }
}