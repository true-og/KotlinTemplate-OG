package plugin

import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import net.trueog.diamondbankog.DiamondBankAPI.DiamondBankException.*
import net.trueog.diamondbankog.PostgreSQL.ShardType
import net.trueog.utilitiesog.UtilitiesOG
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import java.util.concurrent.ExecutionException


class Listeners : Listener {
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        KotlinTemplateOG.scope.launch {
            val playerShards = try {
                KotlinTemplateOG.diamondBankAPI.getPlayerShards(event.player.uniqueId, ShardType.ALL).await()
            } catch (e: EconomyDisabledException) {
                UtilitiesOG.trueogMessage(event.player, "<red>The economy is disabled.")
                return@launch
            } catch (e: TransactionsLockedException) {
                UtilitiesOG.trueogMessage(event.player, "<red>Your transactions are locked.")
                return@launch
            } catch (e: OtherException) {
                UtilitiesOG.trueogMessage(event.player, "<red>Something went wrong.")
                return@launch
            }

            val shardsInBank = playerShards.shardsInBank
            val shardsInInventory = playerShards.shardsInInventory
            val shardsInEnderChest = playerShards.shardsInEnderChest
            if (shardsInBank == null || shardsInInventory == null || shardsInEnderChest == null) {
                UtilitiesOG.trueogMessage(event.player, "<red>An error has occurred.")
                return@launch
            }
            val totalBalance = shardsInBank + shardsInInventory + shardsInEnderChest

            // Send a message to the player with their balance.
            UtilitiesOG.trueogMessage(event.player, "&BYour balance is: &e$totalBalance&B Diamonds.")
            UtilitiesOG.logToConsole(
                "[Template-OG]",
                "The player: " + event.player + "'s <aqua>balance</aqua> is: " + totalBalance + "&B Diamonds"
            )
        }
    }
}
