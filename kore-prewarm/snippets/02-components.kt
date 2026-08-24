import io.github.ayfri.kore.arguments.chatcomponents.text
import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.arguments.colors.Color
import io.github.ayfri.kore.arguments.components.item.*
import io.github.ayfri.kore.arguments.types.literals.self
import io.github.ayfri.kore.commands.give
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.functions.function
import io.github.ayfri.kore.generated.Enchantments
import io.github.ayfri.kore.generated.Items
import io.github.ayfri.kore.pack.pack

fun playground() = dataPack("prewarm_components") {
	pack { description = textComponent("Prewarm: item components") }

	val soulReaper = Items.NETHERITE_SWORD {
		customName(textComponent("Soul Reaper") {
			bold = true
			color = Color.DARK_PURPLE
		})

		lore(
			textComponent("") +
				text("A blade forged from the void,") { italic = true; color = Color.DARK_GRAY } +
				text("harvesting souls with every strike.") { italic = true; color = Color.DARK_GRAY } +
				text("Legendary Artifact") { bold = true; color = Color.GOLD }
		)

		enchantments(
			mapOf(
				Enchantments.SHARPNESS to 10,
				Enchantments.UNBREAKING to 10,
				Enchantments.MENDING to 1,
				Enchantments.FIRE_ASPECT to 2,
			)
		)

		rarity(Rarities.EPIC)
		unbreakable()
		enchantmentGlintOverride(true)
	}

	function("give_soul_reaper") {
		give(self(), soulReaper)
	}
}
