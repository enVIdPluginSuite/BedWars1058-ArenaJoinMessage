package dev.etran.arenajoinmessage.settings

import dev.etran.arenajoinmessage.util.SoundCompat
import dev.etran.arenajoinmessage.util.Text
import net.md_5.bungee.api.chat.ClickEvent
import org.bukkit.Sound
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.logging.Logger

class SoundSettings(val sound: Sound, val volume: Float, val pitch: Float)

/** Immutable snapshot of config.yml, rebuilt on every reload. */
class PluginSettings(
    val enabled: Boolean,
    val states: Set<String>,
    val cooldownMillis: Long,
    val hideWhenFull: Boolean,
    val minPlayers: Int,
    val arenaBlacklist: Set<String>,
    val groupBlacklist: Set<String>,
    val excludePlayersInArenas: Boolean,
    val onlyWorlds: Set<String>,
    val allowOptOut: Boolean,
    val lines: List<String>,
    val hover: List<String>,
    val clickAction: ClickEvent.Action,
    val clickCommand: String,
    val sound: SoundSettings?,
    val joinCommand: String,
    val gamemodes: Map<String, String>,
    val gamemodeFallback: String,
    private val messages: Map<String, String>,
) {
    /** A plugin message with `&` colors translated and `{placeholders}` filled in. */
    fun message(key: String, vars: Map<String, String> = emptyMap()): String =
        Text.color(Text.fill(messages[key] ?: "&c[missing message: $key]", vars))

    fun prefixed(key: String, vars: Map<String, String> = emptyMap()): String =
        message("prefix") + message(key, vars)

    companion object {
        fun load(config: FileConfiguration, logger: Logger): PluginSettings {
            fun lower(path: String) = config.getStringList(path).map { it.lowercase(Locale.ROOT) }.toSet()

            val actionName = config.getString("message.click.action", "RUN_COMMAND")!!.uppercase(Locale.ROOT)
            val action = when (actionName) {
                "RUN_COMMAND" -> ClickEvent.Action.RUN_COMMAND
                "SUGGEST_COMMAND" -> ClickEvent.Action.SUGGEST_COMMAND
                else -> {
                    logger.warning("message.click.action must be RUN_COMMAND or SUGGEST_COMMAND (was '$actionName'), using RUN_COMMAND.")
                    ClickEvent.Action.RUN_COMMAND
                }
            }

            var sound: SoundSettings? = null
            if (config.getBoolean("message.sound.enabled", false)) {
                val name = config.getString("message.sound.name", "")!!
                val resolved = SoundCompat.resolve(name)
                if (resolved == null) {
                    logger.warning("message.sound.name '$name' does not exist on this server version, the sound is disabled.")
                } else {
                    sound = SoundSettings(
                        resolved,
                        config.getDouble("message.sound.volume", 1.0).toFloat(),
                        config.getDouble("message.sound.pitch", 1.0).toFloat(),
                    )
                }
            }

            val gamemodes = HashMap<String, String>()
            config.getConfigurationSection("gamemodes")?.getKeys(false)?.forEach { key ->
                if (key != "fallback") gamemodes[key.lowercase(Locale.ROOT)] = Text.color(config.getString("gamemodes.$key", key)!!)
            }

            val messages = HashMap<String, String>()
            config.getConfigurationSection("messages")?.getKeys(false)?.forEach { key ->
                messages[key] = config.getString("messages.$key", "")!!
            }

            return PluginSettings(
                enabled = config.getBoolean("enabled", true),
                states = lower("trigger.states"),
                cooldownMillis = (config.getDouble("trigger.cooldown-seconds", 0.0) * 1000).toLong().coerceAtLeast(0),
                hideWhenFull = config.getBoolean("trigger.hide-when-full", true),
                minPlayers = config.getInt("trigger.min-players", 1),
                arenaBlacklist = lower("trigger.arena-blacklist"),
                groupBlacklist = lower("trigger.group-blacklist"),
                excludePlayersInArenas = config.getBoolean("recipients.exclude-players-in-arenas", true),
                onlyWorlds = lower("recipients.only-worlds"),
                allowOptOut = config.getBoolean("recipients.allow-opt-out", true),
                lines = config.getStringList("message.lines"),
                hover = config.getStringList("message.hover"),
                clickAction = action,
                clickCommand = config.getString("message.click.command", "/ajm join {arena}")!!,
                sound = sound,
                joinCommand = config.getString("join.command", "bw join {arena}")!!,
                gamemodes = gamemodes,
                gamemodeFallback = Text.color(config.getString("gamemodes.fallback", "{vs}")!!),
                messages = messages,
            )
        }
    }
}
