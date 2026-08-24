import io.github.ayfri.kore.exportAsStrings

fun main() {
	val globals = js("globalThis")

	try {
		val files = js("({})")
		playground().exportAsStrings().forEach { (path, content) -> files[path] = content }
		globals.__koreFiles = files
	} catch (throwable: Throwable) {
		globals.__koreError = throwable.stackTraceToString()
	}
}
