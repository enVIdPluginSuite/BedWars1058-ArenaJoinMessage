package dev.etran.arenajoinmessage.util

import org.bukkit.Bukkit
import org.bukkit.ChatColor

/** Text helpers: color codes (including hex on 1.16+) and {placeholder} replacement. */
object Text {

    /** True on 1.16 and every newer version (including the 26.x naming scheme). */
    val supportsHex: Boolean by lazy {
        // "1.8.8-R0.1-SNAPSHOT", "1.21.4-R0.1-SNAPSHOT", "26.3-R0.1-SNAPSHOT"
        val numbers = Regex("""\d+""").findAll(Bukkit.getBukkitVersion().substringBefore('-')).map { it.value.toInt() }.toList()
        val major = numbers.getOrElse(0) { 1 }
        val minor = numbers.getOrElse(1) { 0 }
        major > 1 || minor >= 16
    }

    private val hexPattern = Regex("""&#([0-9a-fA-F]{6})""")

    /** Translates `&a` style codes and `&#RRGGBB` hex codes into section-sign codes. */
    fun color(input: String): String {
        val withHex = hexPattern.replace(input) { match ->
            if (supportsHex) {
                val hex = match.groupValues[1]
                "§x" + hex.map { "§$it" }.joinToString("")
            } else {
                ""
            }
        }
        return ChatColor.translateAlternateColorCodes('&', withHex)
    }

    /** Replaces every `{key}` in [template] with its value. Values are inserted as-is (not color translated). */
    fun fill(template: String, vars: Map<String, String>): String {
        var result = template
        for ((key, value) in vars) {
            result = result.replace("{$key}", value)
        }
        return result
    }
}
