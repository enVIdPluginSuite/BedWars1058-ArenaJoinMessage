package dev.etran.arenajoinmessage.settings

import dev.etran.arenajoinmessage.util.ConfigEditor
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

    /** Re-reads data.yml. Builds the new set first so readers never see a half-loaded (empty) set. */
    fun load() {
        val loaded = HashSet<UUID>()
        if (file.exists()) {
            val yaml = YamlConfiguration.loadConfiguration(file)
            for (raw in yaml.getStringList("opted-out")) {
                try {
                    loaded.add(UUID.fromString(raw.trim()))
                } catch (ignored: IllegalArgumentException) {
                    plugin.logger.warning("Ignoring invalid UUID '$raw' in data.yml")
                }
            }
        }
        optedOut.retainAll(loaded)
        optedOut.addAll(loaded)
    }

    fun isOptedOut(id: UUID): Boolean = optedOut.contains(id)

    /** Flips the preference and returns true if the player now receives notifications. */
    @Synchronized
    fun toggle(id: UUID): Boolean {
        val receives = if (optedOut.remove(id)) true else { optedOut.add(id); false }
        save()
        return receives
    }

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("opted-out", optedOut.map { it.toString() }.sorted())
        try {
            plugin.dataFolder.mkdirs()
            // Atomic: a crash while saving can never wipe everybody's preference.
            ConfigEditor.writeAtomically(file, yaml.saveToString())
        } catch (e: IOException) {
            plugin.logger.warning("Could not save data.yml: ${e.message}")
        }
    }
}
