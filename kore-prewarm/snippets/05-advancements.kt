import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.features.advancements.AdvancementFrameType
import io.github.ayfri.kore.features.advancements.advancement
import io.github.ayfri.kore.features.advancements.display
import io.github.ayfri.kore.generated.Items
import io.github.ayfri.kore.pack.pack

fun playground() = dataPack("prewarm_advancements") {
	pack { description = textComponent("Prewarm: advancements") }

	advancement("root") {
		display(Items.DIAMOND_SWORD, "Getting started", "Made with Kore") {
			frame = AdvancementFrameType.CHALLENGE
		}
	}
}
