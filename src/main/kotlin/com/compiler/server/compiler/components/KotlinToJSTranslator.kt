package com.compiler.server.compiler.components

import com.compiler.server.common.components.KotlinEnvironment
import com.compiler.server.common.components.usingTempDirectory
import com.compiler.server.kore.KoreCompileSettings
import com.compiler.server.kore.KoreProgress
import com.compiler.server.model.*
import com.compiler.server.utils.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.cli.js.KotlinWasmCompiler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.io.encoding.Base64
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

private val JS_BUILTINS_ALIAS_NAME_REGEX =
    Regex("""import\s+\*\s+as\s+(\S+)\s+from\s+'\./$JS_DEFAULT_MODULE_NAME\.$JS_BUILTINS_POSTFIX\.mjs';""")

@Service
class KotlinToJSTranslator(
    private val compilerArgumentsUtil: CompilerArgumentsUtil,
    private val jsCompilerArguments: Set<ExtendedCompilerArgument>,
    private val wasmCompilerArguments: Set<ExtendedCompilerArgument>,
    private val composeWasmCompilerArguments: Set<ExtendedCompilerArgument>,
    private val kotlinEnvironment: KotlinEnvironment,
    private val koreCompileSettings: KoreCompileSettings,
) {
    companion object {
        internal const val JS_IR_CODE_BUFFER = "playground.output?.buffer_1;\n"

        internal val JS_IR_OUTPUT_REWRITE = """
        if (typeof get_output !== "undefined") {
          get_output();
          output = new BufferedOutput();
          _.output = get_output();
        }
        """.trimIndent()

        const val BEFORE_MAIN_CALL_LINE = 4

        /** UMD headers list their dependencies within the first few lines; scanning further is wasted work. */
        private const val UMD_PREAMBLE_LENGTH = 16 * 1024

        private val UMD_REQUIRE_REGEX = Regex("""require\('\./([^']+\.js)'\)""")

        private const val ANCHOR_MODULE_NAME = "kore-anchor"

        private val log = LoggerFactory.getLogger(KotlinToJSTranslator::class.java)
    }

    /**
     * A klib built from [KoreCompileSettings.anchorSources], linked into every compile as one more library.
     *
     * The IR cache exports from each library exactly what other modules use, so a snippet touching another part of
     * Kore than the previous one made it re-lower those Kore files and regenerate all of `Kore-kore.js`: ~11 s for
     * switching between two examples. This module keeps using everything the examples use, so switching between
     * them leaves Kore untouched, ~3 s.
     *
     * The linker only loads what something reaches in a library, so each snippet's `playground()` is called from a
     * `@JsExport` function, a root it always keeps. That also makes the entry chunk import the anchor's, so it ships
     * like any library chunk: ~50 kB that never change, downloaded once by a client keeping chunks by hash. Built by
     * the first compile, inside the compile gate. Sources that stopped compiling, typically after a Kore bump, are
     * left out with a warning instead of failing every compile.
     */
    private val anchorKlib: String? by lazy {
        val workspace = koreCompileSettings.anchorDirectory ?: return@lazy null
        val sources = koreCompileSettings.anchorSources?.takeIf { it.isDirectory() }?.listDirectoryEntries("*.kt")?.sorted()
        var files = sources.orEmpty().mapIndexed { index, source ->
            val text = "package kore.anchor.a$index\n\n${source.readText()}\n\n@JsExport\nfun anchor$index() {\n    playground()\n}\n"
            ProjectFile(text = text, name = source.name)
        }

        while (files.isNotEmpty()) {
            val inputDir = (workspace / "src").also { it.toFile().deleteRecursively() }.createDirectories()
            val klibPath = (workspace / "klib").also { it.toFile().deleteRecursively() }.toFile().canonicalPath
            val ioFiles = files.writeToIoFiles(inputDir)
            val arguments = compilerArgumentsUtil.convertCompilerArgumentsToCompilationString(
                jsCompilerArguments,
                compilerArgumentsUtil.PREDEFINED_JS_FIRST_PHASE_ARGUMENTS,
                emptyMap()
            ).replacingValueOf("-ir-output-name") { ANCHOR_MODULE_NAME } + "-ir-output-dir=$klibPath"

            val result = K2JSCompiler().tryCompilation(inputDir, ioFiles, ioFiles.map { it.toFile().canonicalPath } + arguments)
            val broken = result.compilerDiagnostics.map.filterValues { it.any { error -> error.severity == ProjectSeveriry.ERROR } }.keys
            if (result is Compiled && broken.isEmpty()) return@lazy klibPath.also { log.info("Anchor module built from ${files.size} snippets") }

            log.warn("Anchor snippets left out, they no longer compile: $broken")
            files = files.filter { it.name !in broken }.takeIf { it.size < files.size }.orEmpty()
        }

        null
    }

    /** Replaces the value following [flag], the shape `convertCompilerArgumentsToCompilationString` gives string arguments. */
    private fun List<String>.replacingValueOf(flag: String, value: (String) -> String) =
        mapIndexed { index, argument -> if (index > 0 && this[index - 1] == flag) value(argument) else argument }

    fun translateJs(
        files: List<ProjectFile>,
        arguments: List<String>,
        jsCompilerArguments: JsCompilerArguments,
        translate: (List<ProjectFile>, List<String>, JsCompilerArguments) -> CompilationResult<JsTranslationOutput>
    ): TranslationJSResult = try {
        val compilationResult = translate(files, arguments, jsCompilerArguments)
        val output = when (compilationResult) {
            is Compiled<JsTranslationOutput> -> compilationResult.result
            is NotCompiled -> null
        }
        TranslationJSResult(
            jsCode = output?.entry,
            compilerDiagnostics = compilationResult.compilerDiagnostics,
            jsFiles = output?.files,
        )
    } catch (e: Exception) {
        TranslationJSResult(exception = e.toExceptionDescriptor())
    }

    fun translateWasm(
        files: List<ProjectFile>,
        debugInfo: Boolean,
        projectType: ProjectType,
        userCompilerArguments: JsCompilerArguments,
        translate: (
            List<ProjectFile>,
            ProjectType,
            Boolean,
            JsCompilerArguments
        ) -> CompilationResult<WasmTranslationSuccessfulOutput>
    ): TranslationResultWithJsCode {
        return try {

            val outputFileName = when (projectType) {
                ProjectType.WASM -> WASM_DEFAULT_MODULE_NAME
                // The compose wasm compiler transforms klib uniqueName and escapes angle brackets with underscores, 
                // resulting in a different outputFileName than regular wasm.
                ProjectType.COMPOSE_WASM -> "_${WASM_DEFAULT_MODULE_NAME}_"
                else -> throw IllegalStateException("Wasm should have wasm or compose-wasm project type")
            }

            val compilationResult = translate(
                files,
                projectType,
                debugInfo,
                userCompilerArguments
            )
            val wasmCompilationOutput = when (compilationResult) {
                is Compiled<WasmTranslationSuccessfulOutput> -> compilationResult.result
                is NotCompiled -> return TranslationJSResult(compilerDiagnostics = compilationResult.compilerDiagnostics)
            }
            TranslationWasmResult(
                jsCode = mergeWasmOutputIntoOneJs(wasmCompilationOutput, outputFileName),
                compilerDiagnostics = compilationResult.compilerDiagnostics
            )
        } catch (e: Exception) {
            TranslationWasmResult(exception = e.toExceptionDescriptor())
        }
    }

    fun doTranslateWithIr(
        files: List<ProjectFile>,
        arguments: List<String>,
        userCompilerArguments: JsCompilerArguments
    ): CompilationResult<JsTranslationOutput> {
        val workspace = koreCompileSettings.snippetDirectory
            ?: return usingTempDirectory { translateWithIrIn(it, files, arguments, userCompilerArguments) }.requireOutput()

        // The IR cache writes nothing for an unchanged program, trusting the previous output to still be in `js/`.
        // When it is gone, one compile as a module of its own rewrites every chunk.
        val result = translateWithIrIn(workspace, files, arguments, userCompilerArguments)
        if (result !is Compiled || result.result != null) return result.requireOutput()

        return usingTempDirectory { translateWithIrIn(it, files, arguments, userCompilerArguments) }.requireOutput()
    }

    private fun CompilationResult<JsTranslationOutput?>.requireOutput() =
        map { it ?: error("Second phase produced no $JS_DEFAULT_MODULE_NAME.js") }

    /**
     * Compiles [files] inside [directory]: sources in `src/`, klib in `klib/`, JavaScript in `js/`, which is kept.
     *
     * The output is `null` when the second phase wrote no entry chunk, which the IR cache does for a program it
     * considers already built.
     */
    private fun translateWithIrIn(
        directory: Path,
        files: List<ProjectFile>,
        arguments: List<String>,
        userCompilerArguments: JsCompilerArguments,
    ): CompilationResult<JsTranslationOutput?> {
        val inputDir = (directory / "src").also { it.toFile().deleteRecursively() }.createDirectories()
        val klibPath = (directory / "klib").also { it.toFile().deleteRecursively() }.toFile().canonicalPath
        val jsDirectory = directory / "js"
        val ioFiles = files.writeToIoFiles(inputDir)
        val filePaths = ioFiles.map { it.toFile().canonicalPath }
        val additionalCompilerArgumentsForKLib =
            compilerArgumentsUtil.convertCompilerArgumentsToCompilationString(
                jsCompilerArguments,
                compilerArgumentsUtil.PREDEFINED_JS_FIRST_PHASE_ARGUMENTS,
                userCompilerArguments.firstPhase
            ) + "-ir-output-dir=$klibPath"
        val jsCompiler = K2JSCompiler()
        var phaseStartedAt = System.nanoTime()

        fun phaseMs(): Long {
            val now = System.nanoTime()
            return ((now - phaseStartedAt) / 1_000_000).also { phaseStartedAt = now }
        }

        KoreProgress.emit("phase", "name" to "klib")

        return jsCompiler.tryCompilation(inputDir, ioFiles, filePaths + additionalCompilerArgumentsForKLib)
            .flatMap {
                KoreProgress.emit("phase", "name" to "js", "previousMs" to phaseMs())
                val anchor = anchorKlib
                val secondPhaseArguments =
                    compilerArgumentsUtil.convertCompilerArgumentsToCompilationString(
                        jsCompilerArguments,
                        compilerArgumentsUtil.PREDEFINED_JS_SECOND_PHASE_ARGUMENTS,
                        userCompilerArguments.secondPhase
                    ).replacingValueOf("-libraries") { if (anchor == null) it else it + File.pathSeparator + anchor } +
                        "-ir-output-dir=${jsDirectory.toFile().canonicalPath}" + "-Xinclude=$klibPath"
                jsCompiler.tryCompilation(
                    inputDir,
                    ioFiles,
                    koreCompileSettings.applyToSecondPhase(secondPhaseArguments)
                )
            }
            .map {
                KoreProgress.emit("phase", "name" to "collect", "previousMs" to phaseMs())
                val entry = jsDirectory / "$JS_DEFAULT_MODULE_NAME.js"
                // An unchanged program comes out of the IR cache named after its klib instead of `-ir-output-name`.
                (jsDirectory / "kotlin_$JS_DEFAULT_MODULE_NAME.js").takeIf { it.exists() }?.moveTo(entry, overwrite = true)
                if (entry.exists()) readJsOutput(jsDirectory, arguments) else null
            }
    }

    /**
     * Collects the second phase output.
     *
     * Upstream emits a single DCE'd bundle, which stays the default. Under the IR build cache the compiler
     * emits one UMD chunk per module instead, and all of them are returned so a browser can evaluate them
     * itself - the ten library chunks are identical between two unrelated snippets and cache cleanly, while
     * only `Kore-kore.js` and `playground.js` genuinely change.
     */
    private fun readJsOutput(jsDirectory: Path, arguments: List<String>): JsTranslationOutput {
        val entryName = "$JS_DEFAULT_MODULE_NAME.js"

        if (!koreCompileSettings.perModuleOutput) {
            val entry = redirectOutput((jsDirectory / entryName).readText().withMainArgumentsIr(arguments))
            return JsTranslationOutput(entry = entry, files = null)
        }

        val chunks = jsDirectory.listDirectoryEntries("*.js").associateTo(linkedMapOf()) { it.name to it.readText() }
        val entry = chunks[entryName]?.withMainArgumentsIr(arguments)
            ?: error("Second phase produced no $entryName in $jsDirectory")
        chunks[entryName] = entry

        return JsTranslationOutput(entry = entry, files = orderChunks(chunks))
    }

    /** 128 bits of SHA-256, plenty to tell a dozen chunks apart across compiles. */
    private fun hashOf(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()), 0, 16)

    /**
     * Sorts the per-module chunks so a plain loader can evaluate them top to bottom.
     *
     * Each chunk is UMD and names what it needs in its `require('./x.js')` preamble, so a depth-first walk
     * over those references is enough - no bundler and no module system on the browser side.
     */
    private fun orderChunks(chunks: Map<String, String>): List<JsFile> {
        val ordered = linkedMapOf<String, String>()
        val visiting = mutableSetOf<String>()

        fun visit(name: String) {
            val text = chunks[name] ?: return
            if (name in ordered || !visiting.add(name)) return

            UMD_REQUIRE_REGEX.findAll(text.take(UMD_PREAMBLE_LENGTH))
                .map { it.groupValues[1] }
                .distinct()
                .forEach(::visit)

            ordered[name] = text
        }

        chunks.keys.sorted().forEach(::visit)
        return ordered.map { JsFile(name = it.key, hash = hashOf(it.value), text = it.value) }
    }

    private fun redirectOutput(code: String): String {
        val listLines = code
            .lineSequence()
            .toMutableList()

        listLines.add(listLines.size - BEFORE_MAIN_CALL_LINE, JS_IR_OUTPUT_REWRITE)
        listLines.add(listLines.size - 1, JS_IR_CODE_BUFFER)
        return listLines.joinToString("\n")
    }


    fun doTranslateWithWasm(
        files: List<ProjectFile>,
        projectType: ProjectType,
        debugInfo: Boolean,
        userCompilerArguments: JsCompilerArguments
    ): CompilationResult<WasmTranslationSuccessfulOutput> =
        usingTempDirectory { inputDir ->
            usingTempDirectory { outputDir ->
                val (defaultCompilerArgs, firstPhasePredefinedArguments, secondPhasePredefinedArguments, outputFileName) = when (projectType) {
                    ProjectType.WASM -> WasmArguments(
                        wasmCompilerArguments,
                        compilerArgumentsUtil.PREDEFINED_WASM_FIRST_PHASE_ARGUMENTS,
                        compilerArgumentsUtil.PREDEFINED_WASM_SECOND_PHASE_ARGUMENTS,
                        WASM_DEFAULT_MODULE_NAME,
                    )

                    ProjectType.COMPOSE_WASM -> WasmArguments(
                        composeWasmCompilerArguments,
                        compilerArgumentsUtil.PREDEFINED_COMPOSE_WASM_FIRST_PHASE_ARGUMENTS,
                        compilerArgumentsUtil.PREDEFINED_COMPOSE_WASM_SECOND_PHASE_ARGUMENTS,
                        "_${WASM_DEFAULT_MODULE_NAME}_" // The compose wasm compiler transforms klib uniqueName and escapes angle brackets with underscores
                    )

                    else -> throw IllegalStateException("Wasm should have wasm or compose-wasm project type")
                }
                val ioFiles = files.writeToIoFiles(inputDir)
                val filePaths = ioFiles.map { it.toFile().canonicalPath }
                val klibPath = (outputDir / "klib").toFile().canonicalPath

                val additionalCompilerArgumentsForKLib =
                    compilerArgumentsUtil.convertCompilerArgumentsToCompilationString(
                        defaultCompilerArgs,
                        firstPhasePredefinedArguments,
                        userCompilerArguments.firstPhase
                    ) + "-ir-output-dir=$klibPath"

                val wasmCompiler = KotlinWasmCompiler()
                wasmCompiler.tryCompilation(inputDir, ioFiles, filePaths + additionalCompilerArgumentsForKLib)
                    .flatMap {
                        val secondPhaseArguments = (compilerArgumentsUtil.convertCompilerArgumentsToCompilationString(
                            defaultCompilerArgs,
                            secondPhasePredefinedArguments,
                            userCompilerArguments.secondPhase
                        ) + "-ir-output-dir=${(outputDir / "wasm").toFile().canonicalPath}" + "-Xinclude=$klibPath").toMutableList()

                        if (debugInfo) secondPhaseArguments.add("-Xwasm-generate-wat")

                        wasmCompiler.tryCompilation(inputDir, ioFiles, secondPhaseArguments)
                    }
                    .map {
                        val wasmOutputDir = outputDir / "wasm"

                        WasmTranslationSuccessfulOutput(
                            jsCode = (wasmOutputDir / "$outputFileName.mjs").readText(),
                            jsBuiltins = (wasmOutputDir / "$outputFileName.$JS_BUILTINS_POSTFIX.mjs")
                                .takeIf { it.exists() }
                                ?.readText(),
                            importObject = (wasmOutputDir / "$outputFileName.$IMPORT_OBJECT_POSTFIX.mjs").readText(),
                            wasm = (wasmOutputDir / "$outputFileName.wasm").readBytes(),
                        )
                    }
            }
        }

    private fun mergeWasmOutputIntoOneJs(
        wasmOutput: WasmTranslationSuccessfulOutput,
        outputFileName: String,
    ): String {
        val staticUrl = kotlinEnvironment.dependenciesStaticUrl

        val importObjectJsContent = wasmOutput.importObject

        val jsBuiltinsAlias = JS_BUILTINS_ALIAS_NAME_REGEX.find(importObjectJsContent)?.groupValues?.get(1)

        val replacedImportObjectContent =
            wasmOutput.jsBuiltins
                .mergeBuiltinsToImport(jsBuiltinsAlias, importObjectJsContent)
                .substituteValidStaticUrl(staticUrl)

        return wasmOutput.jsCode
            .replace(
                "import { importObject, setWasmExports } from './${outputFileName}.import-object.mjs'",
                "const { importObject, setWasmExports } = await import(`data:application/javascript;base64,${
                    Base64.encode(
                        replacedImportObjectContent.toByteArray()
                    )
                }`) "
            )
            .substituteValidStaticUrl(staticUrl)
            .replace(
                "wasmInstance = (await WebAssembly.instantiateStreaming(fetch(new URL('./${outputFileName}.wasm',import.meta.url).href), importObject, wasmOptions)).instance;",
                "wasmInstance = await (async () => {\n" +
                        "  const wasmBase64 = await fetch(`data:application/wasm;base64,${Base64.encode(wasmOutput.wasm)}`); \n" +
                        "  const wasmBinary = new Uint8Array(await wasmBase64.arrayBuffer());\n" +
                        " if (typeof bufferedOutput !== 'undefined') {" +
                        "  importObject.js_code['kotlin.io.printImpl'] = (message) => bufferedOutput.buffer += message\n" +
                        "  importObject.js_code['kotlin.io.printlnImpl'] = (message) => {bufferedOutput.buffer += message;bufferedOutput.buffer += \"\\n\"}}\n" +
                        "  return (await WebAssembly.instantiate(wasmBinary, importObject)).instance;\n" +
                        "  })();"
            ) + "\n export const instantiate = () => Promise.resolve();"
    }

    private fun String?.mergeBuiltinsToImport(
        jsBuiltinsAlias: String?,
        importObjectJsContent: String,
    ): String {
        return this?.toByteArray()?.let { byteContent ->
            importObjectJsContent
                .replace(
                    JS_BUILTINS_ALIAS_NAME_REGEX,
                    jsBuiltInsContent(jsBuiltinsAlias!!, byteContent)
                )
        } ?: importObjectJsContent
    }

    private fun String.substituteValidStaticUrl(staticUrl: String): String {
        val replacedContent = if (staticUrl.isNotEmpty()) {
            replace(
                "from './",
                "from '$staticUrl/",
            )
        } else this

        return replacedContent.fixImports()
    }

    private fun jsBuiltInsContent(
        jsBuiltinsAlias: String,
        byteContent: ByteArray,
    ): String =
        "const $jsBuiltinsAlias = await import(`data:application/javascript;base64, ${
            Base64.encode(
                byteContent
            )
        }`)"


    private fun String.fixImports(): String =
        lineSequence()
            .joinToString("\n") { line ->
                if (line.trimStart().startsWith("import")) {
                    line.replace(".mjs", "-${kotlinEnvironment.dependenciesComposeWasmHash}.mjs")
                } else {
                    line
                }
            }
}

private fun String.withMainArgumentsIr(arguments: List<String>): String {
    val mainIrFunction = """
    |  function mainWrapper() {
    |    main([%s]);
    |  }
  """.trimMargin()

    return replace(
        String.format(mainIrFunction, ""),
        String.format(mainIrFunction, arguments.joinToString { ObjectMapper().writeValueAsString(it) })
    )
}

/** Second phase output: the entry chunk, plus every chunk in evaluation order when per-module is on. */
data class JsTranslationOutput(
    val entry: String,
    val files: List<JsFile>?,
)

data class WasmTranslationSuccessfulOutput(
    val jsCode: String,
    val jsBuiltins: String?,
    val importObject: String,
    val wasm: ByteArray
)
