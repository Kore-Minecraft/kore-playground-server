import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.features.paintingvariant.paintingVariant
import io.github.ayfri.kore.generated.Textures
import io.github.ayfri.kore.pack.pack

fun playground() = dataPack("smoke_paintings") {
	pack { description = textComponent("Smoke: an area no prewarm snippet touches") }

	paintingVariant("wide_kebab", Textures.Painting.KEBAB, height = 1, width = 2) {
		title = textComponent("Wide kebab")
	}
}
