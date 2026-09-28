package com.compiler.server.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize

sealed class ExecutionResult(
  @field:JsonProperty("errors")
  open var compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics(),
  open var exception: ExceptionDescriptor? = null
) {
  var text: String = ""
    set(value) {
      field = unEscapeOutput(value)
    }

  fun addWarnings(warnings: CompilerDiagnostics) {
    compilerDiagnostics = warnings
  }

  fun hasErrors() =
    textWithError() || exception != null || compilerDiagnostics.any { it.severity == ProjectSeveriry.ERROR }

  private fun textWithError() = text.startsWith(ERROR_STREAM_START)
}

class CompilerDiagnosticsSerializer : JsonSerializer<CompilerDiagnostics>() {
  override fun serialize(value: CompilerDiagnostics, gen: JsonGenerator, serializers: SerializerProvider) {
    gen.writeObject(value.map)
  }
}

class CompilerDiagnosticsDeserializer : JsonDeserializer<CompilerDiagnostics>() {
  private val reference = object : TypeReference<Map<String, List<ErrorDescriptor>>>() {}
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): CompilerDiagnostics {
    return p.readValueAs<Map<String, List<ErrorDescriptor>>>(reference).let(::CompilerDiagnostics)
  }
}

@JsonSerialize(using = CompilerDiagnosticsSerializer::class)
@JsonDeserialize(using = CompilerDiagnosticsDeserializer::class)
data class CompilerDiagnostics(
  val map: Map<String, List<ErrorDescriptor>> = mapOf()
): List<ErrorDescriptor> by map.values.flatten()

open class JvmExecutionResult(
  compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics(),
  exception: ExceptionDescriptor? = null,
  var jvmByteCode: String? = null,
): ExecutionResult(compilerDiagnostics, exception)

abstract class TranslationResultWithJsCode(
  open val jsCode: String?,
  compilerDiagnostics: CompilerDiagnostics,
  exception: ExceptionDescriptor?
) : ExecutionResult(compilerDiagnostics, exception)

/**
 * One emitted JS chunk. Only present under per-module output, see `KoreCompileSettings`.
 *
 * [text] is `null` when the caller said it already holds [hash]: most chunks are identical from one compile to
 * the next, so a client keeping them by hash only downloads what changed.
 */
data class JsFile(
  val name: String,
  val hash: String,
  val text: String?,
)

data class TranslationJSResult(
  override val jsCode: String? = null,
  override var exception: ExceptionDescriptor? = null,
  @field:JsonProperty("errors")
  override var compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics(),
  /**
   * Every chunk of a per-module compile, in evaluation order, or `null` for the single-bundle pipeline.
   *
   * [jsCode] still carries the entry chunk in both modes, so a client that only knows `jsCode` keeps
   * working - it just needs the chunks too before that entry can run.
   */
  @field:JsonInclude(JsonInclude.Include.NON_NULL)
  val jsFiles: List<JsFile>? = null,
) : TranslationResultWithJsCode(jsCode, compilerDiagnostics, exception) {
  /** Drops the text of every chunk whose hash is in [known]. */
  fun withoutKnownChunks(known: Set<String>) = when {
    known.isEmpty() || jsFiles == null -> this
    else -> copy(jsFiles = jsFiles.map { if (it.hash in known) it.copy(text = null) else it })
  }
}

data class TranslationWasmResult(
  override val jsCode: String? = null,
  val wasm: ByteArray = byteArrayOf(),
  val wat: String? = null,
  override var exception: ExceptionDescriptor? = null,
  @field:JsonProperty("errors")
  override var compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics()
) : TranslationResultWithJsCode(jsCode, compilerDiagnostics, exception)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
class JunitExecutionResult(
  val testResults: Map<String, List<TestDescription>> = emptyMap(),
  override var exception: ExceptionDescriptor? = null,
  @field:JsonProperty("errors")
  override var compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics(),
  jvmBytecode: String? = null,
) : JvmExecutionResult(compilerDiagnostics, exception, jvmBytecode)

private fun unEscapeOutput(value: String) = value.replace("&amp;lt;".toRegex(), "<")
  .replace("&amp;gt;".toRegex(), ">")
  .replace("\r", "")
