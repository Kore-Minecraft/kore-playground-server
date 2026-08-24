import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.arguments.scores.ScoreboardCriteria
import io.github.ayfri.kore.arguments.types.literals.allPlayers
import io.github.ayfri.kore.arguments.types.literals.self
import io.github.ayfri.kore.commands.scoreboard.scoreboard
import io.github.ayfri.kore.commands.tellraw
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.functions.load
import io.github.ayfri.kore.pack.pack

fun playground() = dataPack("smoke_scoreboard") {
	pack { description = textComponent("Smoke: an area no prewarm snippet touches") }

	load("setup") {
		scoreboard {
			objectives {
				add("smoke", ScoreboardCriteria.DUMMY, "Smoke")
			}

			players {
				add(self(), "smoke", 1)
			}
		}

		tellraw(allPlayers(), textComponent("smoke ready"))
	}
}
