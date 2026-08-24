import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.arguments.types.literals.allPlayers
import io.github.ayfri.kore.arguments.types.literals.self
import io.github.ayfri.kore.commands.say
import io.github.ayfri.kore.commands.tellraw
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.functions.function
import io.github.ayfri.kore.functions.load
import io.github.ayfri.kore.functions.tick
import io.github.ayfri.kore.pack.pack

fun playground() = dataPack("prewarm_functions") {
	pack { description = textComponent("Prewarm: functions and commands") }

	load("bootstrap") {
		tellraw(allPlayers(), textComponent("[prewarm] loaded"))
	}

	tick("loop") {
		say("tick")
	}

	function("hello") {
		tellraw(self(), textComponent("Hello from Kore"))
	}
}
