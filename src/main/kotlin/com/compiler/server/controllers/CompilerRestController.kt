package com.compiler.server.controllers

import com.compiler.server.api.CompilerArgumentResponse
import com.compiler.server.api.RunRequest
import com.compiler.server.api.TestRequest
import com.compiler.server.api.TranslateComposeWasmRequest
import com.compiler.server.api.TranslateJsRequest
import com.compiler.server.api.TranslateWasmRequest
import com.compiler.server.kore.CompileBusyException
import com.compiler.server.kore.KoreProgress
import com.compiler.server.model.CompilerDiagnostics
import com.compiler.server.model.ExecutionResult
import com.compiler.server.model.KotlinTranslatableCompiler
import com.compiler.server.model.Project
import com.compiler.server.model.ProjectFile
import com.compiler.server.model.ProjectType
import com.compiler.server.model.TranslationJSResult
import com.compiler.server.model.TranslationResultWithJsCode
import com.compiler.server.service.CompilerArgumentsService
import com.compiler.server.service.KotlinProjectExecutor
import com.compiler.server.validation.CompilerArgumentsValidator
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import org.jetbrains.kotlin.utils.mapToSetOrEmpty
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/** Newline-delimited JSON, gzipped by Tomcat because it is in `server.compression.mime-types`. */
private const val NDJSON_CONTENT_TYPE = "application/x-ndjson"

private const val NEW_LINE = '\n'.code

@RestController
@RequestMapping(value = ["/api/compiler", "/api/**/compiler"])
class CompilerRestController(
    private val kotlinProjectExecutor: KotlinProjectExecutor,
    private val compilerArgumentsService: CompilerArgumentsService,
    private val compilerArgumentsValidator: CompilerArgumentsValidator,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CompilerRestController::class.java)

    @PostMapping("/run")
    fun executeKotlinProjectEndpoint(
        @RequestBody @Valid request: RunRequest,
        @RequestParam(defaultValue = "false") addByteCode: Boolean,
    ): ExecutionResult {
        return kotlinProjectExecutor.run(
            Project(
                args = request.args,
                files = request.files.map { ProjectFile(name = it.name, text = it.text) },
                compilerArguments = listOf(request.compilerArguments)
            ), addByteCode
        )
    }

    @PostMapping("/test")
    fun testKotlinProjectEndpoint(
        @RequestBody @Valid request: TestRequest,
        @RequestParam(defaultValue = "false") addByteCode: Boolean,
    ): ExecutionResult {
        return kotlinProjectExecutor.test(
            Project(
                args = request.args,
                files = request.files.map { ProjectFile(name = it.name, text = it.text) },
                compilerArguments = listOf(request.compilerArguments)
            ), addByteCode
        )
    }

    /** [known] lists the chunk hashes the caller already holds, comma separated: their texts are left out. */
    @PostMapping("/translate/js")
    fun translateJs(
        @RequestBody @Valid request: TranslateJsRequest,
        @RequestParam(required = false) known: String?,
    ): TranslationResultWithJsCode {
        return kotlinProjectExecutor.convertToJsIr(
            Project(
                args = request.args,
                files = request.files.map { ProjectFile(name = it.name, text = it.text) },
                compilerArguments = listOf(request.firstPhaseCompilerArguments, request.secondPhaseCompilerArguments)
            )
        ).withoutKnownChunks(known)
    }

    private fun TranslationResultWithJsCode.withoutKnownChunks(known: String?): TranslationResultWithJsCode {
        val hashes = known?.split(',')?.filterTo(mutableSetOf()) { it.isNotBlank() }.orEmpty()
        return (this as? TranslationJSResult)?.withoutKnownChunks(hashes) ?: this
    }

    /**
     * Same compile as `/translate/js`, reported as it happens.
     *
     * The body is newline-delimited JSON: every line but the last is a progress event - queue position,
     * compiler phase, the size of the output about to be sent - and the last one carries the very same
     * result object the plain endpoint returns, under `result`. A caller that reads the stream can name
     * what it is waiting on for 6-35 s instead of showing an anonymous spinner.
     *
     * NDJSON rather than server-sent events so the payload stays on a compressed content type: SSE is
     * `text/event-stream`, which Tomcat will not gzip, and the result alone is ~16 MB raw against 1.7 MB
     * gzipped.
     */
    @PostMapping("/translate/js/stream", produces = [NDJSON_CONTENT_TYPE])
    fun translateJsStreaming(
        @RequestBody @Valid request: TranslateJsRequest,
        @RequestParam(required = false) known: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val project = Project(
            args = request.args,
            files = request.files.map { ProjectFile(name = it.name, text = it.text) },
            compilerArguments = listOf(request.firstPhaseCompilerArguments, request.secondPhaseCompilerArguments)
        )

        val body = StreamingResponseBody { output ->
            fun write(line: Map<String, Any?>) {
                output.write(objectMapper.writeValueAsBytes(line))
                output.write(NEW_LINE)
                output.flush()
            }

            try {
                val result = KoreProgress.reportingTo({ progress -> write(mapOf("event" to progress.event) + progress.detail) }) {
                    kotlinProjectExecutor.convertToJsIr(project)
                }.withoutKnownChunks(known)

                // The caller cannot get this from `Content-Length`: the body is gzipped and it reads the decoded stream.
                val sent = (result as? TranslationJSResult)?.jsFiles?.mapNotNull { it.text } ?: listOfNotNull(result.jsCode)
                val reused = (result as? TranslationJSResult)?.jsFiles?.count { it.text == null } ?: 0
                if (sent.isNotEmpty() || reused > 0) {
                    write(mapOf("event" to "output", "chunks" to sent.size, "reused" to reused, "bytes" to sent.sumOf { it.length }))
                }

                write(mapOf("event" to "result", "result" to result))
            } catch (busy: CompileBusyException) {
                write(mapOf("event" to "busy", "message" to busy.message, "retryAfterSeconds" to busy.retryAfterSeconds))
            } catch (failure: Exception) {
                log.warn("Streaming compile failed", failure)
                write(mapOf("event" to "error", "message" to (failure.message ?: failure::class.java.simpleName)))
            }
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, NDJSON_CONTENT_TYPE)
            // Nothing between here and the browser may buffer the stream, or the progress arrives with the result.
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .body(body)
    }

    @PostMapping("/translate/wasm")
    fun translateWasm(
        @RequestBody @Valid request: TranslateWasmRequest,
    ): TranslationResultWithJsCode {
        return kotlinProjectExecutor.convertToWasm(
            Project(
                args = request.args,
                files = request.files.map { ProjectFile(name = it.name, text = it.text) },
                confType = ProjectType.WASM,
                compilerArguments = listOf(request.firstPhaseCompilerArguments, request.secondPhaseCompilerArguments)
            ),
        )
    }

    @PostMapping("/translate/compose-wasm")
    fun translateWasmCompose(
        @RequestBody request: TranslateComposeWasmRequest,
    ): TranslationResultWithJsCode {
        compilerArgumentsValidator.ensureValid(
            ProjectType.COMPOSE_WASM,
            request.firstPhaseCompilerArguments,
            request.secondPhaseCompilerArguments,
        )
        return kotlinProjectExecutor.convertToWasm(
            Project(
                args = request.args,
                files = request.files.map { ProjectFile(name = it.name, text = it.text) },
                confType = ProjectType.COMPOSE_WASM,
                compilerArguments = listOf(request.firstPhaseCompilerArguments, request.secondPhaseCompilerArguments)
            ),
        )
    }

    @PostMapping("/highlight")
    fun highlightEndpoint(@RequestBody project: Project): CompilerDiagnostics =
        kotlinProjectExecutor.highlight(project)


    @GetMapping("/compiler-arguments")
    fun getCompilerArguments(
        @RequestParam projectType: ProjectType,
    ): CompilerArgumentResponse =
        CompilerArgumentResponse(
            compilerArgumentsService.getCompilerArguments(projectType)
                .mapToSetOrEmpty {
                    CompilerArgumentResponse.CompilerArgument(
                        it.name,
                        it.shortName,
                        it.description,
                        it.type,
                        it.disabled,
                        it.predefinedValues,
                        it.valueDescription
                    )
                }
        )

    @PostMapping("/translate")
    @Deprecated("Use /translate/wasm or /translate/js instead")
    fun translate(
        @RequestBody @Valid project: Project,
        @RequestParam(defaultValue = "js") compiler: String,
        @RequestParam(defaultValue = "false") debugInfo: Boolean
    ): TranslationResultWithJsCode {
        val code = when (KotlinTranslatableCompiler.valueOf(compiler.uppercase().replace("-", "_"))) {
            KotlinTranslatableCompiler.JS -> kotlinProjectExecutor.convertToJsIr(project)
            KotlinTranslatableCompiler.WASM -> kotlinProjectExecutor.convertToWasm(
                project,
                debugInfo,
            )

            KotlinTranslatableCompiler.COMPOSE_WASM -> {
                kotlinProjectExecutor.convertToWasm(
                    project,
                    debugInfo,
                )
            }
        }
        return code
    }
}
