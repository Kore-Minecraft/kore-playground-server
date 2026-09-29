import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsCompilerAttribute
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetAttribute

plugins {
    id("base-kotlin-jvm-conventions")
}

/** Kore playground: slim mode keeps only the JVM and JS libraries, the two the compile backend serves. */
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

    /** The JVM folder already ships the stdlib and kotlinx artifacts at the compiler's own versions. */
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

/** Sync so a Kore bump drops the previous jars, preserving the rest of the folder `copyDependencies` fills. */
val copyKoreJvmDependencies by tasks.registering(Sync::class) {
    description = "Syncs the Kore JVM jars into the /highlight classpath."
    from(koreJvmDependency)
    into(libJVMFolder)
    preserve {
        exclude("kore-jvm-*.jar", "oop-jvm-*.jar", "helpers-jvm-*.jar")
    }
}

val copyCompilerPluginDependencies by tasks.creating(Copy::class) {
    from(kotlinCompilerPluginDependency)
    into(compilerPluginsForJVMFolder)
}

/** Sync so a Kore bump drops the previous klibs from the JS compile classpath. */
val copyJSDependencies by tasks.registering(Sync::class) {
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

    /** Kore playground: JS klibs for the compile, with their transitive klibs, and JVM jars for `/highlight`. */
    kotlinJsDependency(libs.bundles.kore)
    koreJvmDependency(libs.bundles.kore)

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