import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.arguments.components.matchers.enchantments
import io.github.ayfri.kore.arguments.numbers.ranges.rangeOrIntStart
import io.github.ayfri.kore.arguments.types.resources.RandomSequenceArgument
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.features.itemmodifiers.functions.explosionDecay
import io.github.ayfri.kore.features.itemmodifiers.functions.setCount
import io.github.ayfri.kore.features.loottables.*
import io.github.ayfri.kore.features.loottables.entries.*
import io.github.ayfri.kore.features.predicates.Predicate
import io.github.ayfri.kore.features.predicates.conditions.*
import io.github.ayfri.kore.features.predicates.providers.constant
import io.github.ayfri.kore.features.predicates.providers.uniform
import io.github.ayfri.kore.features.predicates.sub.predicates
import io.github.ayfri.kore.generated.Enchantments
import io.github.ayfri.kore.generated.Items
import io.github.ayfri.kore.pack.pack

private fun Predicate.withSilkTouchShears() = anyOf {
	matchTool(Items.SHEARS)
	matchTool {
		predicates {
			enchantments(Enchantments.SILK_TOUCH, level = rangeOrIntStart(1))
		}
	}
}

fun playground() = dataPack("prewarm_loottables") {
	pack { description = textComponent("Prewarm: loot tables and predicates") }

	val leaves = listOf(
		Items.OAK_LEAVES to Items.OAK_SAPLING,
		Items.BIRCH_LEAVES to Items.BIRCH_SAPLING,
	)

	leaves.forEach { (leaf, sapling) ->
		val leafId = leaf.asString().removePrefix("minecraft:")

		lootTable("blocks/$leafId") {
			namespace = "minecraft"
			type = LootTableType.BLOCK

			pool {
				bonusRolls = constant(0f)
				entries {
					alternatives {
						children {
							item(leaf) {
								conditions {
									withSilkTouchShears()
								}
							}

							item(sapling) {
								conditions {
									survivesExplosion()
									tableBonus(Enchantments.FORTUNE, 0.05f, 0.0625f, 0.083333f, 0.1f)
								}
							}
						}
					}
				}
			}

			pool {
				bonusRolls = constant(0f)
				entries {
					item(Items.STICK) {
						conditions {
							tableBonus(Enchantments.FORTUNE, 0.02f, 0.022222f, 0.025f, 0.033333f, 0.1f)
						}
						functions {
							setCount(uniform(1f, 2f), false)
							explosionDecay()
						}
					}
				}

				conditions {
					inverted {
						withSilkTouchShears()
					}
				}
			}

			randomSequence = RandomSequenceArgument("blocks/$leafId")
		}
	}
}
