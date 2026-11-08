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
    val minCountdownSeconds: Int,
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
    /**
     * A plugin message with `&` colors translated and `{placeholders}` filled in.
     * Colors are translated BEFORE filling, so values typed by users (e.g. in /ajm set) show up literally.
     */
    fun message(key: String, vars: Map<String, String> = emptyMap()): String =
        Text.fill(Text.color(messages[key] ?: "&c[missing message: $key]"), vars)

    fun prefixed(key: String, vars: Map<String, String> = emptyMap()): String =
        message("prefix") + message(key, vars)

    companion object {
        /** BedWars1058 game states (GameState enum). */
        val KNOWN_STATES = setOf("waiting", "starting", "playing", "restarting")

        fun load(config: FileConfiguration, logger: Logger): PluginSettings {
            /** A list option; a single value written without "- " is accepted too instead of silently becoming empty. */
            fun lowerList(path: String): Set<String> {
                val values = if (config.isList(path)) config.getStringList(path) else listOfNotNull(config.getString(path))
                return values.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
            }

            val states = lowerList("trigger.states")
            (states - KNOWN_STATES).forEach { logger.warning("trigger.states contains unknown state '$it' (known: $KNOWN_STATES).") }
            if (states.isEmpty()) logger.warning("trigger.states is empty, no notification will ever be sent.")

            val actionName = (config.getString("message.click.action") ?: "RUN_COMMAND").trim().uppercase(Locale.ROOT)
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
                val name = config.getString("message.sound.name") ?: ""
                val resolved = SoundCompat.resolve(name)
                if (resolved == null) {
                    logger.warning("message.sound.name '$name' does not exist on this server version, the sound is disabled.")
                } else {
                    sound = SoundSettings(
                        resolved,
                        config.getDouble("message.sound.volume", 1.0).toFloat().coerceIn(0f, 10f),
                        config.getDouble("message.sound.pitch", 1.0).toFloat().coerceIn(0.5f, 2f), // client range
                    )
                }
            }

            val gamemodes = HashMap<String, String>()
            config.getConfigurationSection("gamemodes")?.getKeys(false)?.forEach { key ->
                if (key != "fallback") gamemodes[key.lowercase(Locale.ROOT)] = Text.color(config.getString("gamemodes.$key") ?: key)
            }

            // Keys from the file AND from the built-in defaults, so a message deleted from config.yml
            // (or added in a newer plugin version) falls back to the default instead of "[missing message]".
            val messageKeys = LinkedHashSet<String>()
            config.defaults?.getConfigurationSection("messages")?.getKeys(false)?.let { messageKeys.addAll(it) }
            config.getConfigurationSection("messages")?.getKeys(false)?.let { messageKeys.addAll(it) }
            val messages = messageKeys.associateWith { config.getString("messages.$it") ?: "" }

            val cooldownSeconds = config.getDouble("trigger.cooldown-seconds", 0.0)
            return PluginSettings(
                enabled = config.getBoolean("enabled", true),
                states = states,
                cooldownMillis = if (cooldownSeconds.isFinite()) (cooldownSeconds * 1000).toLong().coerceAtLeast(0) else 0,
                hideWhenFull = config.getBoolean("trigger.hide-when-full", true),
                minPlayers = config.getInt("trigger.min-players", 1).coerceAtLeast(1),
                minCountdownSeconds = config.getInt("trigger.min-countdown-seconds", 3).coerceAtLeast(0),
                arenaBlacklist = lowerList("trigger.arena-blacklist"),
                groupBlacklist = lowerList("trigger.group-blacklist"),
                excludePlayersInArenas = config.getBoolean("recipients.exclude-players-in-arenas", true),
                onlyWorlds = lowerList("recipients.only-worlds"),
                allowOptOut = config.getBoolean("recipients.allow-opt-out", true),
                lines = config.getStringList("message.lines"),
                hover = config.getStringList("message.hover"),
                clickAction = action,
                clickCommand = config.getString("message.click.command") ?: "/ajm join {id}",
                sound = sound,
                joinCommand = (config.getString("join.command") ?: "").trim(),
                gamemodes = gamemodes,
                gamemodeFallback = Text.color(config.getString("gamemodes.fallback") ?: "{vs}"),
                messages = messages,
            )
        }
    }
}
