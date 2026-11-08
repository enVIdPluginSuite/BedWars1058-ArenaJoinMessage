package dev.etran.arenajoinmessage.settings

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Remembers which players turned the notifications off (plugins/<name>/data.yml). */
class PlayerPrefs(private val plugin: JavaPlugin) {

    private val file = File(plugin.dataFolder, "data.yml")
    private val optedOut: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun load() {
        optedOut.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (raw in yaml.getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(raw))
            } catch (ignored: IllegalArgumentException) {
                plugin.logger.warning("Ignoring invalid UUID '$raw' in data.yml")
            }
        }
    }

    fun isOptedOut(id: UUID): Boolean = optedOut.contains(id)

    /** Flips the preference and returns true if the player now receives notifications. */
    fun toggle(id: UUID): Boolean {
        val receives = !optedOut.add(id)
        if (receives) optedOut.remove(id)
        save()
        return receives
    }

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("opted-out", optedOut.map { it.toString() })
        try {
            plugin.dataFolder.mkdirs()
            yaml.save(file)
        } catch (e: IOException) {
            plugin.logger.warning("Could not save data.yml: ${e.message}")
        }
    }
}
