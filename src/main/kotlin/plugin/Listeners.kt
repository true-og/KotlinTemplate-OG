package plugin

import java.util.UUID
import kotlinx.coroutines.launch
import net.trueog.diamondbankog.DiamondBankException
import net.trueog.utilitiesog.UtilitiesOG
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent

class Listeners : Listener {

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {

        // Avoid touching the event inside the coroutine; capture what you need up front.
        val player = event.player
        val uuid: UUID = player.uniqueId
        val playerName: String = player.name

        // DB call is potentially slow -> off main thread.
        KotlinTemplateOG.scope.launch {
            KotlinTemplateOG.diamondBankAPI
                .getTotalShards(uuid)
                .fold(
                    onSuccess = { totalShards ->
                        val prettyDiamonds = KotlinTemplateOG.diamondBankAPI.shardsToDiamonds(totalShards)

                        UtilitiesOG.trueogMessage(
                            player,
                            "&BYour balance is: &e$prettyDiamonds&B (&e$totalShards&B shards).",
                        )

                        UtilitiesOG.logToConsole(
                            "[Template-OG]",
                            "Player $playerName balance is: $totalShards shards ($prettyDiamonds).",
                        )
                    },
                    onFailure = { e ->
                        val msg =
                            when (e) {
                                is DiamondBankException.EconomyDisabledException -> "<red>The economy is disabled."
                                is DiamondBankException.DatabaseException ->
                                    "<red>Something went wrong with the database."
                                else -> "<red>Failed to fetch your balance."
                            }

                        UtilitiesOG.trueogMessage(player, msg)
                    },
                )
        }
    }
}
