package dev.etran.arenajoinmessage.command

import dev.etran.arenajoinmessage.ArenaJoinMessagePlugin
import dev.etran.arenajoinmessage.util.ConfigEditor
import dev.etran.arenajoinmessage.util.Text
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.io.IOException
import java.util.Locale

/** /ajm [help|toggle|reload|preview|get|set|join] */
class AjmCommand(private val plugin: ArenaJoinMessagePlugin) : CommandExecutor, TabCompleter {

    private val settings get() = plugin.settings

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
        plugin.reloadAll()
        reply(sender, "reloaded")
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
        val raw = args.drop(2).joinToString(" ")
        val vars = mapOf("path" to path, "value" to raw)
        val config = plugin.config

        val current = config.get(path)
        if (current is ConfigurationSection || current is List<*>) return reply(sender, "set-not-scalar", vars)
        if (current == null) {
            // Only brand-new keys inside an existing section are allowed (e.g. a new gamemodes.<x> entry).
            val parent = path.substringBeforeLast('.', "")
            if (parent.isEmpty() || !config.isConfigurationSection(parent)) return reply(sender, "set-unknown-path", vars)
        }

        val parsed: Any = when (current) {
            is Boolean -> when (raw.lowercase(Locale.ROOT)) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> return reply(sender, "set-invalid-value", vars + ("type" to "true/false"))
            }
            is Int, is Long, is Double -> raw.toIntOrNull() ?: raw.toDoubleOrNull()
                ?: return reply(sender, "set-invalid-value", vars + ("type" to "number"))
            else -> raw
        }

        val file = plugin.dataFolder.resolve("config.yml")
        val result = try {
            ConfigEditor.set(file, path, ConfigEditor.literal(parsed))
        } catch (e: IOException) {
            return reply(sender, "set-failed", vars + ("error" to (e.message ?: "unknown error")))
        }
        when (result) {
            ConfigEditor.Result.UPDATED, ConfigEditor.Result.ADDED -> {
                plugin.reloadAll()
                reply(sender, "set-ok", mapOf("path" to path, "value" to parsed.toString()))
            }
            ConfigEditor.Result.NOT_SCALAR -> reply(sender, "set-not-scalar", vars)
            ConfigEditor.Result.NOT_FOUND -> reply(sender, "set-unknown-path", vars)
        }
    }

    /** The command run when a player clicks the chat message. */
    private fun join(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: return reply(sender, "players-only")
        if (!player.hasPermission("arenajoinmessage.join")) return reply(sender, "no-permission")
        val name = args.getOrNull(1) ?: return reply(sender, "join-usage")

        val arenaUtil = plugin.bedWars.arenaUtil
        val arena = arenaUtil.getArenaByName(name) ?: return reply(sender, "join-no-arena")
        if (arenaUtil.getArenaByPlayer(player) != null) return reply(sender, "join-already-in-arena")
        val status = arena.status.name.lowercase(Locale.ROOT)
        if (status != "waiting" && status != "starting") return reply(sender, "join-not-joinable")

        // Hand over to the real join command so parties, VIP slots and every other rule keep working.
        val command = Text.fill(settings.joinCommand, mapOf("arena" to arena.arenaName)).removePrefix("/")
        plugin.server.dispatchCommand(player, command)
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
                "join" -> plugin.bedWars.arenaUtil.arenas.map { it.arenaName }
                else -> emptyList()
            }
            3 -> if (admin && args[0].equals("set", ignoreCase = true)) valueHints(args[1]) else emptyList()
            else -> emptyList()
        }
        val typed = args.last().lowercase(Locale.ROOT)
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
}
