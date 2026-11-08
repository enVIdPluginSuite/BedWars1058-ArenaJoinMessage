package dev.etran.arenajoinmessage.util

import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * Sound lookup that works on every version. `Sound` is an enum up to 1.21.2 and an interface
 * afterwards, so the lookup goes through reflection instead of a compiled-in `Sound.valueOf`.
 */
object SoundCompat {

    fun resolve(name: String): Sound? = try {
        Sound::class.java.getMethod("valueOf", String::class.java)
            .invoke(null, name.trim().uppercase().replace('.', '_')) as? Sound
    } catch (ignored: Throwable) {
        null
    }

    fun play(player: Player, sound: Sound, volume: Float, pitch: Float) {
        try {
            player.playSound(player.location, sound, volume, pitch)
        } catch (ignored: Throwable) {
            // never let a sound problem break the notification
        }
    }
}
