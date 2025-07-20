package plugin

import kotlinx.coroutines.launch
import net.trueog.diamondbankog.DiamondBankException
import net.trueog.utilitiesog.UtilitiesOG
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent

class Listeners : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        // Make sure to never run any other Bukkit functions in launch {} (for example accessing players' inventories)
        // launch {} is needed in this case since getTotalShards() calls a database which can be too slow to run on
        // the main thread
        KotlinTemplateOG.scope.launch {
            val totalShards =
                KotlinTemplateOG.diamondBankAPI.getTotalShards(event.player.uniqueId).getOrElse { e ->
                    when (e) {
                        is DiamondBankException.EconomyDisabledException -> {
                            UtilitiesOG.trueogMessage(event.player, "<red>The economy is disabled.")
                            return@launch
                        }

                        is DiamondBankException.TransactionsLockedException -> {
                            UtilitiesOG.trueogMessage(event.player, "<red>Transactions are currently locked for you.")
                            return@launch
                        }

                        is DiamondBankException.DatabaseException -> {
                            UtilitiesOG.trueogMessage(event.player, "<red>Something went wrong with the database.")
                            return@launch
                        }
                    }
                }

            // Send a message to the player with their balance.
            UtilitiesOG.trueogMessage(event.player, "&BYour balance is: &e$totalShards&B Diamond Shards.")
            UtilitiesOG.logToConsole(
                "[Template-OG]",
                "The player: " + event.player + "'s <aqua>balance</aqua> is: $totalShards&B Diamond Shards",
            )
        }
    }
}
