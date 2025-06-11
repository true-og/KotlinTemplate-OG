// This is free and unencumbered software released into the public domain.
// Author: Sekalol15
package plugin

import kotlinx.coroutines.*
import net.trueog.diamondbankog.DiamondBankAPIKotlin
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin


// Extending this class is standard bukkit boilerplate for any plugin, or else the server software won't load the classes.
class KotlinTemplateOG : JavaPlugin() {
    companion object {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        lateinit var plugin: KotlinTemplateOG
        lateinit var diamondBankAPI: DiamondBankAPIKotlin
    }

    override fun onEnable() {
        plugin = this
        Config.load()

        val diamondBankAPIProvider = server.servicesManager.getRegistration(DiamondBankAPIKotlin::class.java)
        if (diamondBankAPIProvider == null) {
            this.logger.severe("DiamondBank-OG API is null")
            Bukkit.getPluginManager().disablePlugin(this)
            return
        }
        diamondBankAPI = diamondBankAPIProvider.getProvider()


        this.server.pluginManager.registerEvents(Listeners(), this)
    }

    override fun onDisable() {
        scope.cancel()

        runBlocking {
            scope.coroutineContext[Job]?.join()
        }
    }

}
