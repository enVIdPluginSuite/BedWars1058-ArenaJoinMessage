package dev.etran.arenajoinmessage.notify

import com.andrei1058.bedwars.api.BedWars
import com.andrei1058.bedwars.api.arena.IArena
import dev.etran.arenajoinmessage.ArenaJoinMessagePlugin
import dev.etran.arenajoinmessage.settings.PluginSettings
import dev.etran.arenajoinmessage.util.ChatCompat
import dev.etran.arenajoinmessage.util.SoundCompat
import dev.etran.arenajoinmessage.util.Text
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Decides whether a join is announced, builds the message and sends it. */
class JoinNotifier(private val plugin: ArenaJoinMessagePlugin) {

    /** Joins that happened in the current tick, per arena instance. */
    private class PendingJoins(val arena: IArena) {
        val joiners = LinkedHashMap<UUID, Player>()
        var closed = false
    }

    private val pending = ConcurrentHashMap<String, PendingJoins>()
    private val lastAnnounced = ConcurrentHashMap<String, Long>()

    fun clearCooldowns() = lastAnnounced.clear()

    /** Drops everything that is still queued (plugin disable). */
    fun shutdown() {
        pending.clear()
        lastAnnounced.clear()
    }

    /**
     * Called from the join event. BedWars1058 fires that event BEFORE it adds the player, and a party
     * fires one event per member in the same tick (members first, the leader last). So joins are
     * collected per arena and announced ONCE, one tick later, when the arena really contains them.
     */
    fun queue(arena: IArena, joiner: Player) {
        val key = arenaKey(arena)
        while (true) {
            var created = false
            val batch = pending.computeIfAbsent(key) { created = true; PendingJoins(arena) }
            val added = synchronized(batch) {
                if (batch.closed) {
                    false // the task is sending this batch right now; start a new one
                } else {
                    batch.joiners.remove(joiner.uniqueId) // re-insert so the most recent joiner is last
                    batch.joiners[joiner.uniqueId] = joiner
                    true
                }
            }
            if (!added) {
                pending.remove(key, batch)
                continue
            }
            // runTask is thread safe, so this also works if another plugin adds players off the main thread.
            if (created) {
                try {
                    plugin.server.scheduler.runTask(plugin, Runnable { flush(key, batch) })
                } catch (e: Exception) {
                    // plugin is being disabled: drop the batch so it can never get stuck in the map
                    pending.remove(key, batch)
                }
            }
            return
        }
    }

    private fun flush(key: String, batch: PendingJoins) {
        val joiners = synchronized(batch) {
            batch.closed = true
            ArrayList(batch.joiners.values)
        }
        pending.remove(key, batch)
        try {
            announce(batch.arena, joiners)
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Could not send the join message for arena '$key'", t)
        }
    }

    private fun announce(arena: IArena, joiners: List<Player>) {
        val settings = plugin.settings
        if (!settings.enabled || settings.lines.isEmpty()) return
        val bedWars = plugin.bedWars ?: return

        // Only joins that really happened and are still there (the join can fail after the event,
        // or the player can leave / disconnect within the tick).
        val joined = joiners.filter { it.isOnline && arena.isPlayer(it) }
        // The last one is the party leader when a party joins together.
        val shown = joined.lastOrNull() ?: return

        val status = arena.status?.name?.lowercase(Locale.ROOT) ?: return
        if (status !in settings.states) return
        val arenaName = arena.arenaName ?: return
        val id = arenaKey(arena)
        if (arenaName.lowercase(Locale.ROOT) in settings.arenaBlacklist || id.lowercase(Locale.ROOT) in settings.arenaBlacklist) return
        val group = arena.group
        if (group != null && group.lowercase(Locale.ROOT) in settings.groupBlacklist) return

        val playerCount = arena.players?.size ?: return
        val maxPlayers = arena.maxPlayers
        if (playerCount < settings.minPlayers) return
        if (settings.hideWhenFull && maxPlayers > 0 && playerCount >= maxPlayers) return

        // Nearly started: BedWars1058 refuses joins at countdown <= 1, so a click would only fail.
        if (status == "starting") {
            val countdown = arena.startingTask?.countdown
            if (countdown != null && countdown < settings.minCountdownSeconds) return
        }

        if (settings.cooldownMillis > 0) {
            val now = System.currentTimeMillis()
            val last = lastAnnounced[id]
            if (last != null && now - last < settings.cooldownMillis) return
            lastAnnounced[id] = now
            // Auto-scale clones get a fresh id every game; forget old ones so the map cannot grow forever.
            if (lastAnnounced.size > 64) lastAnnounced.entries.removeIf { now - it.value >= settings.cooldownMillis }
        }

        val vars = placeholders(settings, arena, shown, status, playerCount)
        val lines = buildLines(settings, vars)
        if (lines.isEmpty()) return

        val skip = joined.mapTo(HashSet()) { it.uniqueId }
        // Copy: other plugins may react to our messages / the sound and change the player list.
        for (recipient in ArrayList(plugin.server.onlinePlayers)) {
            if (recipient.uniqueId in skip) continue
            if (!shouldReceive(settings, bedWars, recipient)) continue
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
            "id" to "sample_arena",
            "group" to "Default",
            "gamemode" to gamemodeName(settings, 4, 2),
            "players" to "1",
            "max" to "16",
            "status" to "waiting",
        )
        player.sendMessage(settings.prefixed("preview-header"))
        send(settings, player, buildLines(settings, vars))
    }

    private fun shouldReceive(settings: PluginSettings, bedWars: BedWars, recipient: Player): Boolean {
        if (!recipient.isOnline) return false
        if (!recipient.hasPermission("arenajoinmessage.receive")) return false
        if (settings.allowOptOut && plugin.prefs.isOptedOut(recipient.uniqueId)) return false
        if (settings.onlyWorlds.isNotEmpty() && recipient.world.name.lowercase(Locale.ROOT) !in settings.onlyWorlds) return false
        // getArenaByPlayer covers players AND spectators.
        if (settings.excludePlayersInArenas && bedWars.arenaUtil.getArenaByPlayer(recipient) != null) return false
        return true
    }

    private fun placeholders(settings: PluginSettings, arena: IArena, joiner: Player, status: String, playerCount: Int): Map<String, String> {
        val display = arena.displayName
        return mapOf(
            "player" to joiner.name,
            "displayname" to (joiner.displayName ?: joiner.name),
            // The arena display name comes straight from the arena config and may contain & codes.
            "map" to if (display.isNullOrBlank()) arena.arenaName else Text.color(display),
            "arena" to arena.arenaName,
            "id" to arenaKey(arena),
            "group" to (arena.group ?: ""),
            "gamemode" to gamemodeName(settings, arena.teams?.size ?: 0, arena.maxInTeam),
            "players" to playerCount.toString(),
            "max" to arena.maxPlayers.toString(),
            "status" to status,
        )
    }

    private fun gamemodeName(settings: PluginSettings, teams: Int, perTeam: Int): String {
        settings.gamemodes["${teams}x$perTeam"]?.let { return it }
        settings.gamemodes["$perTeam"]?.let { return it }
        val vs = List(teams.coerceIn(1, 16)) { perTeam.toString() }.joinToString("v")
        return Text.fill(
            settings.gamemodeFallback,
            mapOf("vs" to vs, "teams" to teams.toString(), "per_team" to perTeam.toString()),
        )
    }

    private fun buildLines(settings: PluginSettings, vars: Map<String, String>): List<TextComponent> {
        val hover = settings.hover.map { Text.fill(Text.color(it), vars) }
        // Color codes in a clicked command make the client send a section sign, and the server kicks the
        // player for "illegal characters in chat". {displayname} usually has colors, so strip them.
        val command = Text.strip(Text.fill(settings.clickCommand, vars)).trim()
        val click = if (command.isEmpty()) null else ChatCompat.click(settings.clickAction, command)
        return settings.lines.map { ChatCompat.line(Text.fill(Text.color(it), vars), hover, click) }
    }

    private fun send(settings: PluginSettings, recipient: Player, lines: List<TextComponent>) {
        for (line in lines) recipient.spigot().sendMessage(line)
        settings.sound?.let { SoundCompat.play(recipient, it.sound, it.volume, it.pitch) }
    }

    companion object {
        /**
         * Unique id of an arena INSTANCE. With auto-scale every clone shares the template's arena name
         * but has its own world name, so the world name is what tells two games apart.
         */
        fun arenaKey(arena: IArena): String = arena.worldName?.takeIf { it.isNotBlank() } ?: arena.arenaName
    }
}
