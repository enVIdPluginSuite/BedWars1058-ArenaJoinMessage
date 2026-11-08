package dev.etran.arenajoinmessage.listener

import com.andrei1058.bedwars.api.events.player.PlayerJoinArenaEvent
import dev.etran.arenajoinmessage.ArenaJoinMessagePlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ArenaJoinListener(private val plugin: ArenaJoinMessagePlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArenaJoin(event: PlayerJoinArenaEvent) {
        if (event.isSpectator) return // spectators cannot be joined by clicking, so nothing to announce
        val arena = event.arena
        val player = event.player

        // BedWars1058 calls this event before it adds the player, so wait one tick for the real player count.
        plugin.server.scheduler.runTask(plugin, Runnable { plugin.notifier.announce(arena, player) })
    }
}
