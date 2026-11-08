package dev.etran.arenajoinmessage.command

import com.andrei1058.bedwars.api.BedWars
import com.andrei1058.bedwars.api.arena.IArena
import dev.etran.arenajoinmessage.ArenaJoinMessagePlugin
import dev.etran.arenajoinmessage.notify.JoinNotifier
import dev.etran.arenajoinmessage.util.ConfigEditor
import dev.etran.arenajoinmessage.util.Text
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

/** /ajm [help|toggle|reload|preview|get|set|join] */
class AjmCommand(private val plugin: ArenaJoinMessagePlugin) : CommandExecutor, TabCompleter {

    private val settings get() = plugin.settings

    /** Last click per player, to ignore double clicks / spam clicking the message. */
    private val lastJoinClick = HashMap<UUID, Long>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "help"
        when (sub) {
            "help", "?" -> help(sender, label)
            "toggle" -> toggle(sender)
            "join" -> join(sender, args)
            "reload" -> adminOnly(sender) { reload(sender) }
            "preview" -> adminOnly(sender) { preview(sender) }
            "get" -> adminOnly(sender) { get(sender, args) }
            "set" -> adminOnly(sender) { set(sender, args) }
            else -> reply(sender, "unknown-subcommand")
        }
        return true
    }

    // --- sub commands --------------------------------------------------------------------------

    private fun help(sender: CommandSender, label: String) {
        sender.sendMessage(Text.color("&b&lArenaJoinMessage &7- commands"))
        sender.sendMessage(Text.color("&e/$label toggle &7- turn the notifications on/off for yourself"))
        if (sender.hasPermission("arenajoinmessage.admin")) {
            sender.sendMessage(Text.color("&e/$label reload &7- reload config.yml"))
            sender.sendMessage(Text.color("&e/$label preview &7- show the notification to yourself"))
            sender.sendMessage(Text.color("&e/$label get <path> &7- show a config value"))
            sender.sendMessage(Text.color("&e/$label set <path> <value> &7- change a config value in game"))
        }
    }

    private fun toggle(sender: CommandSender) {
        val player = sender as? Player ?: return reply(sender, "players-only")
        if (!player.hasPermission("arenajoinmessage.toggle")) return reply(sender, "no-permission")
        if (!settings.allowOptOut) return reply(sender, "opt-out-disabled")
        val receives = plugin.prefs.toggle(player.uniqueId)
        reply(sender, if (receives) "toggled-on" else "toggled-off")
    }

    private fun reload(sender: CommandSender) {
        val error = plugin.reloadAll()
        if (error == null) reply(sender, "reloaded") else reply(sender, "reload-failed", mapOf("error" to error))
    }

    private fun preview(sender: CommandSender) {
        val player = sender as? Player ?: return reply(sender, "players-only")
        plugin.notifier.preview(player)
    }

    private fun get(sender: CommandSender, args: Array<out String>) {
        val path = args.getOrNull(1) ?: return sender.sendMessage(Text.color("&cUsage: /ajm get <path>"))
        val value = plugin.config.get(path)
        if (value == null || value is ConfigurationSection) return reply(sender, "set-unknown-path", mapOf("path" to path))
        reply(sender, "get-value", mapOf("path" to path, "value" to value.toString()))
    }

    private fun set(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) return sender.sendMessage(Text.color("&cUsage: /ajm set <path> <value>"))
        val path = args[1]
        var raw = args.drop(2).joinToString(" ")
        if (raw == "\"\"" || raw == "''") raw = "" // the only way to type an empty value
        val vars = mapOf("path" to path, "value" to raw)
        val config = plugin.config

        val current = config.get(path)
        if (current is ConfigurationSection || current is List<*>) return reply(sender, "set-not-scalar", vars)
        // Brand-new keys are only allowed for new gamemode names. Anything else is a typo and would
        // otherwise be "saved" as a junk key that does nothing.
        if (current == null && !(path.startsWith("gamemodes.") && path.count { it == '.' } == 1)) {
            return reply(sender, "set-unknown-path", vars)
        }

        val parsed: Any = when (current) {
            is Boolean -> when (raw.lowercase(Locale.ROOT)) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> return reply(sender, "set-invalid-value", vars + ("type" to "true/false"))
            }
            is Number -> {
                val asInt = raw.toIntOrNull()
                val asDouble = raw.toDoubleOrNull()?.takeIf { it.isFinite() } // no NaN / Infinity
                when {
                    asInt != null -> asInt
                    asDouble != null && path in DECIMAL_PATHS -> asDouble
                    else -> return reply(sender, "set-invalid-value", vars + ("type" to if (path in DECIMAL_PATHS) "number" else "whole number"))
                }
            }
            else -> raw
        }

        val file = plugin.dataFolder.resolve("config.yml")
        val backup = try {
            Files.readAllBytes(file.toPath())
        } catch (e: IOException) {
            return reply(sender, "set-failed", vars + ("error" to (e.message ?: "cannot read config.yml")))
        }
        val result = try {
            ConfigEditor.set(file, path, ConfigEditor.literal(parsed))
        } catch (e: IOException) {
            return reply(sender, "set-failed", vars + ("error" to (e.message ?: "unknown error")))
        }
        when (result) {
            ConfigEditor.Result.UPDATED, ConfigEditor.Result.ADDED -> {
                val error = plugin.reloadAll()
                // Double check the edit did what we meant. If not (an unusual config layout), undo it.
                val now = plugin.config.get(path)
                if (error != null || !sameValue(now, parsed)) {
                    restore(file, backup)
                    return reply(sender, "set-failed", vars + ("error" to (error ?: "the file layout could not be edited safely, edit config.yml by hand")))
                }
                reply(sender, "set-ok", mapOf("path" to path, "value" to parsed.toString()))
            }
            ConfigEditor.Result.NOT_SCALAR -> reply(sender, "set-not-scalar", vars)
            ConfigEditor.Result.NOT_FOUND -> reply(sender, "set-unknown-path", vars)
        }
    }

    private fun sameValue(actual: Any?, expected: Any): Boolean = when {
        actual is Number && expected is Number -> actual.toDouble() == expected.toDouble()
        else -> actual?.toString() == expected.toString()
    }

    private fun restore(file: java.io.File, backup: ByteArray) {
        try {
            ConfigEditor.writeAtomically(file, String(backup, Charsets.UTF_8))
            plugin.reloadAll()
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Could not restore config.yml after a failed /ajm set", e)
        }
    }

    /** The command run when a player clicks the chat message. */
    private fun join(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: return reply(sender, "players-only")
        if (!player.hasPermission("arenajoinmessage.join")) return reply(sender, "no-permission")
        val name = args.getOrNull(1) ?: return reply(sender, "join-usage")

        // Ignore a second click within a second (double click, lag): the first one is still being handled.
        val now = System.currentTimeMillis()
        val last = lastJoinClick[player.uniqueId]
        if (last != null && now - last < CLICK_DEBOUNCE_MS) return
        lastJoinClick[player.uniqueId] = now
        if (lastJoinClick.size > 256) lastJoinClick.entries.removeIf { now - it.value >= CLICK_DEBOUNCE_MS }

        val bedWars = plugin.bedWars ?: return reply(sender, "join-no-arena")
        val arenaUtil = bedWars.arenaUtil
        if (arenaUtil.getArenaByPlayer(player) != null) return reply(sender, "join-already-in-arena")
        val arena = resolveArena(bedWars, name) ?: return reply(sender, "join-no-arena")
        if (!isJoinable(arena)) return reply(sender, "join-not-joinable")

        val command = settings.joinCommand
        try {
            if (command.isEmpty()) {
                // Exactly what /bw join does for an arena, without its name lookup (which prefers group names
                // and, with auto-scale, picks a random clone). Parties, VIP and full checks happen inside.
                arena.addPlayer(player, false)
            } else {
                // {arena} and {id} both mean the unique instance id here, which /bw join also accepts.
                val id = JoinNotifier.arenaKey(arena)
                val filled = Text.fill(command, mapOf("arena" to id, "id" to id, "player" to player.name))
                plugin.server.dispatchCommand(player, filled.removePrefix("/"))
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Joining ${player.name} to ${arena.arenaName} failed", e)
            reply(sender, "join-not-joinable")
        }
    }

    /**
     * Finds the arena from the clicked link. Links carry the unique world id; a plain arena name (old links,
     * typed by hand) is accepted too and, with auto-scale clones, resolves to a clone that can still be joined.
     */
    private fun resolveArena(bedWars: BedWars, name: String): IArena? {
        val util = bedWars.arenaUtil
        try {
            util.getArenaByIdentifier(name)?.let { return it }
        } catch (ignored: LinkageError) {
            // older API without getArenaByIdentifier
        }
        val byName = util.getArenaByName(name)
        if (byName != null && isJoinable(byName)) return byName
        val joinableClone = util.arenas
            .filter { it.arenaName.equals(name, ignoreCase = true) && isJoinable(it) }
            .filter { it.maxPlayers <= 0 || it.players.size < it.maxPlayers }
            .maxByOrNull { it.players.size }
        return joinableClone ?: byName
    }

    private fun isJoinable(arena: IArena): Boolean {
        return when (arena.status?.name?.lowercase(Locale.ROOT)) {
            "waiting" -> true
            // BedWars1058 refuses joins once the countdown is at 1.
            "starting" -> (arena.startingTask?.countdown ?: Int.MAX_VALUE) > 1
            else -> false
        }
    }

    // --- tab completion ------------------------------------------------------------------------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("arenajoinmessage.admin")
        val options: List<String> = when (args.size) {
            1 -> buildList {
                add("help"); add("toggle")
                if (admin) addAll(listOf("reload", "preview", "get", "set"))
            }
            2 -> when (args[0].lowercase(Locale.ROOT)) {
                "get", "set" -> if (admin) settingPaths() else emptyList()
                "join" -> plugin.bedWars?.arenaUtil?.arenas?.mapNotNull { it.arenaName }?.distinct() ?: emptyList()
                else -> emptyList()
            }
            3 -> if (admin && args[0].equals("set", ignoreCase = true)) valueHints(args[1]) else emptyList()
            else -> emptyList()
        }
        val typed = (args.lastOrNull() ?: "").lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(typed) }
    }

    private fun settingPaths(): List<String> =
        plugin.config.getKeys(true).filter {
            val value = plugin.config.get(it)
            value != null && value !is ConfigurationSection && value !is List<*>
        }

    private fun valueHints(path: String): List<String> = when (val value = plugin.config.get(path)) {
        is Boolean -> listOf("true", "false")
        null -> emptyList()
        else -> if (path == "message.click.action") listOf("RUN_COMMAND", "SUGGEST_COMMAND") else listOf(value.toString())
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun reply(sender: CommandSender, key: String, vars: Map<String, String> = emptyMap()) {
        sender.sendMessage(settings.prefixed(key, vars))
    }

    private inline fun adminOnly(sender: CommandSender, action: () -> Unit) {
        if (sender.hasPermission("arenajoinmessage.admin")) action() else reply(sender, "no-permission")
    }

    private companion object {
        const val CLICK_DEBOUNCE_MS = 1000L
        /** Numeric options that accept decimals; every other number is a whole number. */
        val DECIMAL_PATHS = setOf("trigger.cooldown-seconds", "message.sound.volume", "message.sound.pitch")
    }
}
