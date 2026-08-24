import io.github.ayfri.kore.arguments.chatcomponents.textComponent
import io.github.ayfri.kore.arguments.colors.BossBarColor
import io.github.ayfri.kore.bossbar.registerBossBar
import io.github.ayfri.kore.commands.BossBarStyle
import io.github.ayfri.kore.dataPack
import io.github.ayfri.kore.entities.player
import io.github.ayfri.kore.functions.function
import io.github.ayfri.kore.pack.pack

fun playground() = dataPack("prewarm_oop") {
	pack { description = textComponent("Prewarm: the oop module") }

	val target = player("TestPlayer")

	val bar = registerBossBar("my_bar", name) {
		color = BossBarColor.RED
		max = 200
		style = BossBarStyle.NOTCHED_10
		value = 50
	}

	function("bossbar_demo") {
		bar.setValue(100)
		bar.setColor(BossBarColor.BLUE)
		bar.setPlayers(target)
		bar.show()
	}
}
