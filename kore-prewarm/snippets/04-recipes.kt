import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.features.recipes.recipes
import io.github.ayfri.kore.features.recipes.types.ingredient
import io.github.ayfri.kore.features.recipes.types.result
import io.github.ayfri.kore.features.recipes.types.smelting
import io.github.ayfri.kore.features.recipes.types.smoking
import io.github.ayfri.kore.generated.Items
import io.github.ayfri.kore.pack.pack
import io.github.ayfri.kore.pack.packFormat

fun playground() = dataPack("prewarm_recipes") {
	pack {
		minFormat = packFormat(81)
		description = textComponent("Prewarm: recipes")
	}

	recipes {
		smelting("browndye_smelt") {
			ingredient(Items.ROTTEN_FLESH)
			result(Items.BROWN_DYE)
			experience = 0.1
			cookingTime = 200
		}

		smoking("leather_smoke") {
			ingredient(Items.ROTTEN_FLESH)
			result(Items.LEATHER)
			experience = 0.1
			cookingTime = 100
		}
	}
}
