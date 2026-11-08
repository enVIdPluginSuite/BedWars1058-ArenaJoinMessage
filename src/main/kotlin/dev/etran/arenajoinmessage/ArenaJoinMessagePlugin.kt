package dev.etran.arenajoinmessage

import com.andrei1058.bedwars.api.BedWars
import dev.etran.arenajoinmessage.command.AjmCommand
import dev.etran.arenajoinmessage.listener.ArenaJoinListener
import dev.etran.arenajoinmessage.notify.JoinNotifier
import dev.etran.arenajoinmessage.settings.PlayerPrefs
import dev.etran.arenajoinmessage.settings.PluginSettings
import org.bukkit.plugin.java.JavaPlugin

class ArenaJoinMessagePlugin : JavaPlugin() {

    lateinit var bedWars: BedWars
        private set
    lateinit var settings: PluginSettings
        private set
    lateinit var prefs: PlayerPrefs
        private set
    lateinit var notifier: JoinNotifier
        private set

    override fun onEnable() {
        val registration = server.servicesManager.getRegistration(BedWars::class.java)
        if (registration == null) {
            logger.severe("BedWars1058 API not found. Is BedWars1058 installed and enabled? Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        bedWars = registration.provider

        saveDefaultConfig()
        settings = PluginSettings.load(config, logger)
        prefs = PlayerPrefs(this).also { it.load() }
        notifier = JoinNotifier(this)

        server.pluginManager.registerEvents(ArenaJoinListener(this), this)

        val command = getCommand("ajm")
        if (command != null) {
            val handler = AjmCommand(this)
            command.setExecutor(handler)
            command.tabCompleter = handler
        }

        logger.info("Enabled. Arena join messages are ${if (settings.enabled) "ON" else "OFF"}.")
    }

    /** Re-reads config.yml and data.yml. Safe to call at any time. */
    fun reloadAll() {
        reloadConfig()
        settings = PluginSettings.load(config, logger)
        prefs.load()
        notifier.clearCooldowns()
    }
}
