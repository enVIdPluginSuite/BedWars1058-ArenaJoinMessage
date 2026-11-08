package dev.etran.arenajoinmessage.notify

import com.andrei1058.bedwars.api.arena.IArena
import dev.etran.arenajoinmessage.ArenaJoinMessagePlugin
import dev.etran.arenajoinmessage.settings.PluginSettings
import dev.etran.arenajoinmessage.util.ChatCompat
import dev.etran.arenajoinmessage.util.SoundCompat
import dev.etran.arenajoinmessage.util.Text
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Decides whether a join is announced, builds the message and sends it. */
class JoinNotifier(private val plugin: ArenaJoinMessagePlugin) {

    private val lastAnnounced = ConcurrentHashMap<String, Long>()

    fun clearCooldowns() = lastAnnounced.clear()

    /** Called one tick after [joiner] was added to [arena]. */
    fun announce(arena: IArena, joiner: Player) {
        val settings = plugin.settings
        if (!settings.enabled || !joiner.isOnline) return

        // The BedWars1058 event fires *before* the player is added, so verify it really worked.
        if (!arena.isPlayer(joiner)) return

        if (arena.status.name.lowercase(Locale.ROOT) !in settings.states) return
        if (arena.arenaName.lowercase(Locale.ROOT) in settings.arenaBlacklist) return
        val group = arena.group
        if (group != null && group.lowercase(Locale.ROOT) in settings.groupBlacklist) return

        val playerCount = arena.players.size
        if (playerCount < settings.minPlayers) return
        if (settings.hideWhenFull && playerCount >= arena.maxPlayers) return

        if (settings.cooldownMillis > 0) {
            val now = System.currentTimeMillis()
            val last = lastAnnounced[arena.arenaName]
            if (last != null && now - last < settings.cooldownMillis) return
            lastAnnounced[arena.arenaName] = now
        }

        val vars = placeholders(settings, arena, joiner)
        val lines = buildLines(settings, vars)

        for (recipient in plugin.server.onlinePlayers) {
            if (recipient.uniqueId == joiner.uniqueId) continue
            if (!shouldReceive(settings, recipient)) continue
            send(settings, recipient, lines)
        }
    }

    /** Shows the current message to [player] with sample data. */
    fun preview(player: Player) {
        val settings = plugin.settings
        val vars = mapOf(
            "player" to player.name,
            "displayname" to player.displayName,
            "map" to "Sample Map",
            "arena" to "sample_arena",
            "group" to "Default",
            "gamemode" to gamemodeName(settings, 4, 2),
            "players" to "1",
            "max" to "16",
            "status" to "waiting",
        )
        player.sendMessage(settings.prefixed("preview-header"))
        send(settings, player, buildLines(settings, vars))
    }

    private fun shouldReceive(settings: PluginSettings, recipient: Player): Boolean {
        if (!recipient.hasPermission("arenajoinmessage.receive")) return false
        if (settings.allowOptOut && plugin.prefs.isOptedOut(recipient.uniqueId)) return false
        if (settings.onlyWorlds.isNotEmpty() && recipient.world.name.lowercase(Locale.ROOT) !in settings.onlyWorlds) return false
        if (settings.excludePlayersInArenas && plugin.bedWars.arenaUtil.getArenaByPlayer(recipient) != null) return false
        return true
    }

    private fun placeholders(settings: PluginSettings, arena: IArena, joiner: Player): Map<String, String> {
        val display = arena.displayName
        return mapOf(
            "player" to joiner.name,
            "displayname" to joiner.displayName,
            "map" to if (display.isNullOrBlank()) arena.arenaName else display,
            "arena" to arena.arenaName,
            "group" to (arena.group ?: ""),
            "gamemode" to gamemodeName(settings, arena.teams.size, arena.maxInTeam),
            "players" to arena.players.size.toString(),
            "max" to arena.maxPlayers.toString(),
            "status" to arena.status.name.lowercase(Locale.ROOT),
        )
    }

    private fun gamemodeName(settings: PluginSettings, teams: Int, perTeam: Int): String {
        settings.gamemodes["${teams}x$perTeam"]?.let { return it }
        settings.gamemodes["$perTeam"]?.let { return it }
        val vs = List(teams.coerceAtLeast(1)) { perTeam.toString() }.joinToString("v")
        return Text.fill(
            settings.gamemodeFallback,
            mapOf("vs" to vs, "teams" to teams.toString(), "per_team" to perTeam.toString()),
        )
    }

    private fun buildLines(settings: PluginSettings, vars: Map<String, String>): List<TextComponent> {
        val hover = settings.hover.map { Text.fill(Text.color(it), vars) }
        val command = Text.fill(settings.clickCommand, vars)
        val click = ClickEvent(settings.clickAction, command)
        return settings.lines.map { ChatCompat.line(Text.fill(Text.color(it), vars), hover, click) }
    }

    private fun send(settings: PluginSettings, recipient: Player, lines: List<TextComponent>) {
        for (line in lines) recipient.spigot().sendMessage(line)
        settings.sound?.let { SoundCompat.play(recipient, it.sound, it.volume, it.pitch) }
    }
}
