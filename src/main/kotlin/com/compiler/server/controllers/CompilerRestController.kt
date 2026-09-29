package com.compiler.server.controllers

import com.compiler.server.api.CompilerArgumentResponse
import com.compiler.server.api.RunRequest
import com.compiler.server.api.TestRequest
import com.compiler.server.api.TranslateComposeWasmRequest
import com.compiler.server.api.TranslateJsRequest
import com.compiler.server.api.TranslateWasmRequest
import com.compiler.server.kore.CompileBusyException
import com.compiler.server.kore.CompileEvent
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

    /** Same compile as `/translate/js` as [CompileEvent] lines, NDJSON rather than SSE since Tomcat never gzips `text/event-stream`. */
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
            fun write(event: CompileEvent) {
                output.write(objectMapper.writeValueAsBytes(event))
                output.write(NEW_LINE)
                output.flush()
            }

            try {
                val result = KoreProgress.reportingTo(::write) { kotlinProjectExecutor.convertToJsIr(project) }.withoutKnownChunks(known)

                /** The decoded size, which a browser reading the gzipped body cannot get from `Content-Length`. */
                val sent = (result as? TranslationJSResult)?.jsFiles?.mapNotNull { it.text } ?: listOfNotNull(result.jsCode)
                val reused = (result as? TranslationJSResult)?.jsFiles?.count { it.text == null } ?: 0
                if (sent.isNotEmpty() || reused > 0) write(CompileEvent.Output(sent.size, reused, sent.sumOf { it.length }))

                write(CompileEvent.Result(result))
            } catch (busy: CompileBusyException) {
                write(CompileEvent.Busy(busy.message, busy.retryAfterSeconds))
            } catch (failure: Exception) {
                log.warn("Streaming compile failed", failure)
                write(CompileEvent.Failed(failure.message ?: failure::class.java.simpleName))
            }
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, NDJSON_CONTENT_TYPE)
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
